package dev.frame.monitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class LiveMonitor implements AutoCloseable {
    final FrameLayout view;
    private final Activity activity;
    private final FrameLayout picture;
    private final TextureView texture;
    private final TextView message;
    private final Grid grid;
    private final CameraManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private CameraDevice camera;
    private CameraCaptureSession session;
    private Surface surface;
    private Size frameSize;
    private String selected;
    private int generation;
    private boolean active = true;
    private boolean zoom;

    LiveMonitor(Activity activity, Runnable exit) {
        this.activity = activity;
        manager = activity.getSystemService(CameraManager.class);
        view = new FrameLayout(activity);
        view.setBackgroundColor(Color.BLACK);
        view.setKeepScreenOn(true);
        picture = new FrameLayout(activity);
        view.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        texture = new TextureView(activity);
        picture.addView(texture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        grid = new Grid(activity);
        grid.setVisibility(View.GONE);
        picture.addView(grid, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        picture.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitFrame());
        message = new TextView(activity);
        message.setGravity(Gravity.CENTER);
        message.setPadding(16, 8, 16, 8);
        message.setBackgroundColor(0xb8000000);
        view.addView(message, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        LinearLayout controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER);
        controls.setBackgroundColor(0xb8000000);
        view.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        addButton(controls, "‹ Назад", exit);
        addButton(controls, "Вход", this::chooseInput);
        addButton(controls, "Сетка", () -> grid.setVisibility(grid.isShown() ? View.GONE : View.VISIBLE));
        addButton(controls, "Зум", () -> {
            zoom = !zoom;
            texture.setScaleX(zoom ? 2f : 1f);
            texture.setScaleY(zoom ? 2f : 1f);
        });
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture value, int w, int h) { open(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture value, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture value) { release(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture value) {}
        });
        message.setText("Подключи UVC-вход и нажми «Вход».");
    }

    private void chooseInput() {
        List<String> inputs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        try {
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_EXTERNAL) continue;
                inputs.add(id);
                labels.add("Внешний видеовход " + id);
            }
        } catch (CameraAccessException error) {
            message.setText("Android не смог получить список видеовходов.");
            return;
        }
        if (inputs.isEmpty()) {
            message.setText("Android не видит внешний видеовход. Проверь режим USB Streaming или UVC-карту захвата.");
            return;
        }
        new AlertDialog.Builder(activity).setTitle("Видеовход")
                .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                    selected = inputs.get(index);
                    open();
                }).show();
    }

    private void open() {
        release();
        if (!active || selected == null || !texture.isAvailable()) return;
        int ticket = generation;
        try {
            CameraCharacteristics info = manager.getCameraCharacteristics(selected);
            android.hardware.camera2.params.StreamConfigurationMap config =
                    info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = config == null ? null : config.getOutputSizes(SurfaceTexture.class);
            if (sizes == null || sizes.length == 0) {
                message.setText("Видеовход не предоставил формат для предпросмотра.");
                return;
            }
            frameSize = sizes[0];
            for (Size size : sizes) {
                if (distance(size) < distance(frameSize)) frameSize = size;
            }
            fitFrame();
            message.setText("Открываю видеовход…");
            manager.openCamera(selected, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (ticket != generation) { device.close(); return; }
                    camera = device;
                    startPreview(device, ticket);
                }
                @Override public void onDisconnected(CameraDevice device) {
                    device.close();
                    fail(ticket, "Видеовход отключён.");
                }
                @Override public void onError(CameraDevice device, int error) {
                    device.close();
                    fail(ticket, "Android не открыл видеовход. Код " + error);
                }
            }, main);
        } catch (CameraAccessException | RuntimeException error) {
            fail(ticket, "Видеовход недоступен. Переподключи устройство и выбери вход снова.");
        }
    }

    private void startPreview(CameraDevice device, int ticket) {
        SurfaceTexture target = texture.getSurfaceTexture();
        if (target == null) { release(); return; }
        target.setDefaultBufferSize(frameSize.getWidth(), frameSize.getHeight());
        surface = new Surface(target);
        try {
            CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(surface);
            device.createCaptureSession(java.util.Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession current) {
                    if (ticket != generation) { current.close(); return; }
                    session = current;
                    try {
                        current.setRepeatingRequest(request.build(), null, main);
                        message.setText("UVC  ·  " + frameSize.getWidth() + " × " + frameSize.getHeight());
                    } catch (CameraAccessException | RuntimeException error) {
                        fail(ticket, "Не удалось запустить видеопоток.");
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession current) {
                    current.close();
                    fail(ticket, "Видеовход не принял формат предпросмотра.");
                }
            }, main);
        } catch (CameraAccessException | RuntimeException error) {
            fail(ticket, "Не удалось настроить видеопоток.");
        }
    }

    private static long distance(Size size) {
        return Math.abs(size.getWidth() - 1280L) + Math.abs(size.getHeight() - 720L);
    }

    private void fitFrame() {
        if (frameSize == null || picture.getWidth() == 0 || picture.getHeight() == 0) return;
        double scale = Math.min((double) picture.getWidth() / frameSize.getWidth(),
                (double) picture.getHeight() / frameSize.getHeight());
        int width = Math.max(1, (int) (frameSize.getWidth() * scale));
        int height = Math.max(1, (int) (frameSize.getHeight() * scale));
        for (View child : new View[]{texture, grid}) {
            if (child.getLayoutParams().width != width || child.getLayoutParams().height != height)
                child.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
        }
    }

    private void fail(int ticket, String text) {
        if (ticket != generation) return;
        release();
        message.setText(text);
    }

    void pause() { active = false; release(); }
    void resume() { active = true; open(); }
    @Override public void close() { pause(); }

    private void release() {
        generation++;
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (surface != null) { surface.release(); surface = null; }
    }

    private void addButton(LinearLayout row, String title, Runnable action) {
        Button button = new Button(activity);
        button.setText(title);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private static final class Grid extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Grid(Context context) {
            super(context);
            paint.setColor(0x99ffffff);
            paint.setStrokeWidth(getResources().getDisplayMetrics().density);
        }
        @Override protected void onDraw(Canvas canvas) {
            for (int i = 1; i <= 2; i++) {
                float x = getWidth() * i / 3f;
                float y = getHeight() * i / 3f;
                canvas.drawLine(x, 0, x, getHeight(), paint);
                canvas.drawLine(0, y, getWidth(), y, paint);
            }
        }
    }
}
