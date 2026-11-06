package dev.frame.monitor;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.mtp.MtpDevice;
import android.mtp.MtpObjectInfo;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class CameraFiles implements AutoCloseable {
    static final int ROOT = -1;
    private final MtpDevice mtp;

    CameraFiles(UsbManager manager, UsbDevice device) throws IOException {
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) throw new IOException("Android не открыл USB-устройство.");
        mtp = new MtpDevice(device);
        boolean opened = false;
        try {
            opened = mtp.open(connection);
            if (!opened) throw new IOException("Камера не открыла сеанс PTP/MTP. Проверь режим USB.");
        } finally {
            if (!opened) connection.close();
        }
    }

    int[] storages() throws IOException {
        int[] ids = mtp.getStorageIds();
        if (ids == null || ids.length == 0) throw new IOException("Камера не предоставила доступ к картам.");
        return ids;
    }

    String storageName(int id) {
        android.mtp.MtpStorageInfo info = mtp.getStorageInfo(id);
        String name = info == null ? null : info.getDescription();
        return name == null || name.isEmpty() ? "Карта " + Integer.toUnsignedString(id) : name;
    }

    List<Entry> list(int storage, int parent) throws IOException {
        int[] handles = mtp.getObjectHandles(storage, 0, parent);
        if (handles == null) throw new IOException("Камера не вернула содержимое папки.");
        List<Entry> entries = new ArrayList<>();
        for (int handle : handles) {
            MtpObjectInfo info = mtp.getObjectInfo(handle);
            if (info != null) entries.add(new Entry(handle, info.getName(), info.getFormat() == 0x3001));
        }
        entries.sort(Comparator.comparing((Entry item) -> !item.folder)
                .thenComparing(item -> item.name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    void download(Entry item, File file) throws IOException {
        try (ParcelFileDescriptor output = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE)) {
            if (!mtp.importFile(item.handle, output)) throw new IOException("Камера не передала файл.");
        }
    }

    @Override public void close() { mtp.close(); }

    static final class Entry {
        final int handle;
        final String name;
        final boolean folder;
        Entry(int handle, String name, boolean folder) {
            this.handle = handle;
            this.name = name == null || name.isEmpty() ? "Файл-" + handle : name;
            this.folder = folder;
        }
    }
}
