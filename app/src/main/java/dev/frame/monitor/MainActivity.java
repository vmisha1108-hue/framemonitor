package dev.frame.monitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String USB_PERMISSION = "dev.frame.monitor.USB_PERMISSION";
    private static final int OPEN_FILE = 1, SAVE_FILE = 2, CAMERA_PERMISSION = 3;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayDeque<CameraFiles.Entry> folders = new ArrayDeque<>();
    private UsbManager usb;
    private UsbDevice waiting, connected;
    // The session is owned by the I/O executor.
    private CameraFiles camera;
    private int storage, generation;
    private boolean busy, destroyed;
    private LinearLayout body;
    private TextView status;
    private AlertDialog previewDialog;
    private CameraFiles.Entry pendingSave;
    private int pendingSaveGeneration;
    private LiveMonitor monitor;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = waiting;
            if (device == null) return;
            waiting = null;
            if (usb.hasPermission(device)) connect(device);
            else status.setText("Доступ к USB не предоставлен. Нажми «PTP / MTP» для повтора.");
        }
    };

    private final BroadcastReceiver detachReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null && device.equals(waiting)) waiting = null;
            if (device != null && device.equals(connected)) {
                disconnect();
                if (monitor == null) clear("Камера отключена.");
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        usb = getSystemService(UsbManager.class);
        register(permissionReceiver, USB_PERMISSION);
        register(detachReceiver, UsbManager.ACTION_USB_DEVICE_DETACHED);
        showHome();
    }

    private void register(BroadcastReceiver receiver, String action) {
        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    private void showHome() {
        stopMonitor();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        setContentView(root);
        TextView heading = new TextView(this);
        heading.setText("Frame Monitor");
        heading.setTextSize(25);
        root.addView(heading);
        LinearLayout toolbar = new LinearLayout(this);
        root.addView(toolbar);
        toolbar.addView(button("PTP / MTP", this::chooseDevice), new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(button("USB-файлы", this::openFiles), new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(button("Монитор", this::requestMonitor), new LinearLayout.LayoutParams(0, -2, 1));
        status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        clear("PTP / MTP — карты камеры. USB-файлы — накопитель, доступный через Android. Монитор — внешний видеовход.");
    }

    private void chooseDevice() {
        if (busy || waiting != null) return;
        List<UsbDevice> devices = new ArrayList<>();
        for (UsbDevice device : usb.getDeviceList().values()) {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                int type = device.getInterface(i).getInterfaceClass();
                if (type == UsbConstants.USB_CLASS_STILL_IMAGE || type == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                    devices.add(device);
                    break;
                }
            }
        }
        if (devices.isEmpty()) {
            status.setText("PTP/MTP-устройство не найдено. Проверь OTG, кабель данных и режим USB камеры.");
            return;
        }
        CharSequence[] labels = devices.stream().map(device -> {
            String name = device.getProductName();
            return (name == null ? "USB-устройство" : name) +
                    String.format(Locale.ROOT, " · %04x:%04x", device.getVendorId(), device.getProductId());
        }).toArray(CharSequence[]::new);
        new AlertDialog.Builder(this).setTitle("USB-камера").setItems(labels, (dialog, index) -> {
            UsbDevice device = devices.get(index);
            if (usb.hasPermission(device)) connect(device);
            else {
                waiting = device;
                Intent result = new Intent(USB_PERMISSION).setPackage(getPackageName());
                PendingIntent callback = PendingIntent.getBroadcast(this, 0, result,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                usb.requestPermission(device, callback);
            }
        }).show();
    }

    private void connect(UsbDevice device) {
        disconnect();
        connected = device;
        showCards(true);
    }

    private void showCards(boolean open) {
        UsbDevice device = connected;
        folders.clear();
        clear("Читаю карты камеры…");
        read(() -> {
            if (open) camera = new CameraFiles(usb, device);
            List<Card> cards = new ArrayList<>();
            for (int id : camera.storages()) cards.add(new Card(id, camera.storageName(id)));
            return cards;
        }, cards -> {
            clear("Карты камеры");
            for (Card card : cards) body.addView(button(card.name, () -> {
                storage = card.id;
                showFolder();
            }));
        });
    }


    private void showFolder() {
        CameraFiles.Entry folder = folders.peek();
        int parent = folder == null ? CameraFiles.ROOT : folder.handle;
        int card = storage;
        clear("Читаю папку…");
        read(() -> camera.list(card, parent), entries -> {
            clear(folder == null ? "Файлы на карте" : folder.name);
            body.addView(button(folder == null ? "‹ Карты" : "‹ Назад", () -> {
                if (folders.isEmpty()) showCards(false);
                else { folders.pop(); showFolder(); }
            }));
            if (entries.isEmpty()) status.setText("Папка пуста.");
            for (CameraFiles.Entry item : entries) body.addView(button(
                    (item.folder ? "▸  " : "") + item.name, () -> {
                        if (busy) return;
                        if (item.folder) { folders.push(item); showFolder(); }
                        else chooseFileAction(item);
                    }));
        });
    }

    private void chooseFileAction(CameraFiles.Entry item) {
        new AlertDialog.Builder(this).setTitle(item.name)
                .setItems(new String[]{"Просмотреть", "Сохранить копию…"}, (dialog, which) -> {
                    if (connected == null) return;
                    if (which == 0) {
                        String mime = mime(item.name);
                        if (!mime.startsWith("image/") && !mime.startsWith("video/")) {
                            status.setText("Для этого формата доступно сохранение оригинала.");
                            return;
                        }
                        status.setText("Загружаю для просмотра…");
                        read(() -> download(item), file -> showPreview(Uri.fromFile(file), mime, item.name, file),
                                file -> file.delete());
                    } else {
                        pendingSave = item;
                        pendingSaveGeneration = generation;
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                .setType(mime(item.name)).putExtra(Intent.EXTRA_TITLE, item.name);
                        startActivityForResult(intent, SAVE_FILE);
                    }
                }).show();
    }

    private File download(CameraFiles.Entry item) throws IOException {
        File file = File.createTempFile("camera-", ".media", getCacheDir());
        try {
            camera.download(item, file);
            return file;
        } catch (IOException | RuntimeException error) {
            file.delete();
            throw error;
        }
    }

    private void openFiles() {
        if (busy || waiting != null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        startActivityForResult(intent, OPEN_FILE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        CameraFiles.Entry item = pendingSave;
        if (request == SAVE_FILE) pendingSave = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == OPEN_FILE) {
            String type = getContentResolver().getType(uri);
            showPreview(uri, type == null ? "" : type, "Просмотр", null);
        } else if (request == SAVE_FILE) {
            if (item == null || generation != pendingSaveGeneration || connected == null) {
                status.setText("Сеанс камеры завершился. Подключись снова и повтори сохранение.");
                return;
            }
            status.setText("Сохраняю оригинал…");
            read(() -> {
                File file = download(item);
                try (FileInputStream in = new FileInputStream(file);
                     OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("Android не открыл файл назначения.");
                    byte[] buffer = new byte[128 * 1024];
                    int size;
                    while ((size = in.read(buffer)) != -1) out.write(buffer, 0, size);
                } finally {
                    file.delete();
                }
                return item.name;
            }, name -> status.setText("Сохранено: " + name));
        }
    }

    private void showPreview(Uri uri, String mime, String title, File temporary) {
        status.setText("Просмотр: " + title);
        if (mime.startsWith("video/")) {
            VideoView video = new VideoView(this);
            video.setLayoutParams(new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            FrameLayout frame = new FrameLayout(this);
            frame.addView(video);
            AlertDialog dialog = preview(title, frame, () -> {
                video.stopPlayback();
                if (temporary != null) temporary.delete();
            });
            MediaController controls = new MediaController(this);
            video.setMediaController(controls);
            video.setOnErrorListener((player, what, extra) -> {
                dialog.dismiss();
                status.setText("Телефон не смог воспроизвести этот видеофайл.");
                return true;
            });
            video.setVideoURI(uri);
            video.start();
        } else {
            read(() -> {
                try {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                        BitmapFactory.decodeStream(input, null, options);
                    }
                    options.inSampleSize = 1;
                    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 2000)
                        options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                        Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
                        if (bitmap == null) throw new IOException("Формат изображения не поддерживается телефоном.");
                        return bitmap;
                    }
                } finally {
                    if (temporary != null) temporary.delete();
                }
            }, bitmap -> {
                ImageView image = new ImageView(this);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setImageBitmap(bitmap);
                preview(title, image, () -> image.setImageDrawable(null));
            }, Bitmap::recycle);
        }
    }

    private AlertDialog preview(String title, View view, Runnable cleanup) {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(view)
                .setPositiveButton("Закрыть", null).create();
        previewDialog = dialog;
        dialog.setOnDismissListener(ignored -> { cleanup.run(); previewDialog = null; });
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        return dialog;
    }

    private void requestMonitor() {
        if (busy || waiting != null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        else startMonitor();
    }

    @Override public void onRequestPermissionsResult(int code, String[] names, int[] results) {
        super.onRequestPermissionsResult(code, names, results);
        if (code == CAMERA_PERMISSION && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            startMonitor();
    }

    private void startMonitor() {
        disconnect();
        monitor = new LiveMonitor(this, this::showHome);
        setContentView(monitor.view);
    }

    private void stopMonitor() {
        if (monitor != null) { monitor.close(); monitor = null; }
    }

    @Override protected void onStart() { super.onStart(); if (monitor != null) monitor.resume(); }
    @Override protected void onStop() { if (monitor != null) monitor.pause(); super.onStop(); }
    @Override public void onBackPressed() {
        if (monitor != null) showHome();
        else super.onBackPressed();
    }

    private void disconnect() {
        generation++;
        busy = false;
        connected = null;
        folders.clear();
        if (previewDialog != null) previewDialog.dismiss();
        io.execute(() -> {
            if (camera != null) { camera.close(); camera = null; }
        });
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stopMonitor();
        unregisterReceiver(permissionReceiver);
        unregisterReceiver(detachReceiver);
        disconnect();
        io.shutdown();
        super.onDestroy();
    }

    private <T> void read(Task<T> task, Result<T> result) { read(task, result, ignored -> {}); }

    private <T> void read(Task<T> task, Result<T> result, Result<T> discard) {
        busy = true;
        int ticket = generation;
        io.execute(() -> {
            try {
                T value = task.run();
                runOnUiThread(() -> {
                    if (destroyed || ticket != generation) { discard.accept(value); return; }
                    busy = false;
                    result.accept(value);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed || ticket != generation) return;
                    busy = false;
                    status.setText(error.getMessage() == null ? "Операция не удалась." : error.getMessage());
                });
            }
        });
    }

    private void clear(String text) { body.removeAllViews(); status.setText(text); }
    private int dp(int pixels) { return Math.round(pixels * getResources().getDisplayMetrics().density); }
    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private static String mime(String name) {
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (extension.equals("mov")) return "video/quicktime";
        String type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return type == null ? "application/octet-stream" : type;
    }

    private interface Task<T> { T run() throws Exception; }
    private interface Result<T> { void accept(T value); }
    private static final class Card {
        final int id;
        final String name;
        Card(int id, String name) { this.id = id; this.name = name; }
    }
}
