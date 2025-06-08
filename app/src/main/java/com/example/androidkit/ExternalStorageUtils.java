package com.example.androidkit;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 管理外部存储的工具类，用于在应用卸载后仍能保留数据
 * 适配Android各版本的存储权限变更
 */
public class ExternalStorageUtils {
    private static final String TAG = "ExternalStorageManager";
    private static final String HIDDEN_DIR = "Android/syskit"; // 所有版本统一目录
    private static final String FILE_NAME_ANDROID10 = ".sysdata"; // Android 10及以下
    private static final String FILE_NAME_ANDROID11 = "sysdata"; // Android 11+

    /**
     * 保存字符串到外部存储，兼容所有Android主流版本，内容Base64编码，路径高度隐蔽
     */
    public static boolean saveStringToExternalStorage(Context context, String data) {
        if (data == null) {
            Log.e(TAG, "Cannot save null data");
            return false;
        }
        String encoded = Base64.encodeToString(data.getBytes(StandardCharsets.UTF_8), Base64.DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+，用MediaStore写入Documents/Android/syskit/sysdata
            boolean ok = saveStringToMediaStoreSyskitDir(context, encoded);
            if (ok) return true;
            // 回退普通Documents目录
            return saveStringToMediaStoreDocuments(context, encoded);
        } else {
            // Android 10及以下，直接写入根目录/Android/syskit/.sysdata
            File dir = new File(Environment.getExternalStorageDirectory(), HIDDEN_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Failed to create hidden dir: " + dir.getAbsolutePath());
                return false;
            }
            File file = new File(dir, FILE_NAME_ANDROID10);
            try (FileOutputStream fos = new FileOutputStream(file);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(encoded);
                writer.flush();
                Log.d(TAG, "Saved to hidden dir: " + file.getAbsolutePath());
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Error saving to hidden dir", e);
                return false;
            }
        }
    }

    /**
     * 读取外部存储的字符串，自动Base64解码，兼容所有Android主流版本
     */
    public static String readStringFromExternalStorage(Context context) {
        String encoded = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+，查Documents/Android/syskit/sysdata，否则查Documents/sysdata
            encoded = readStringFromMediaStoreSyskitDir(context);
            if (encoded != null) return decodeBase64(encoded);
            encoded = readStringFromMediaStoreDocuments(context);
            return encoded == null ? null : decodeBase64(encoded);
        } else {
            File dir = new File(Environment.getExternalStorageDirectory(), HIDDEN_DIR);
            File file = new File(dir, FILE_NAME_ANDROID10);
            if (!file.exists()) {
                Log.e(TAG, "Hidden file does not exist: " + file.getAbsolutePath());
                return null;
            }
            try (FileInputStream fis = new FileInputStream(file);
                 InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
                 BufferedReader reader = new BufferedReader(isr)) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                if (sb.length() > 0) sb.setLength(sb.length() - 1);
                return decodeBase64(sb.toString());
            } catch (IOException e) {
                Log.e(TAG, "Error reading from hidden dir", e);
                return null;
            }
        }
    }

    // Android 11+专用 MediaStore 写入/读取
    private static boolean saveStringToMediaStoreSyskitDir(Context context, String encoded) {
        try {
            android.content.ContentResolver resolver = context.getContentResolver();
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME, FILE_NAME_ANDROID11);
            values.put(android.provider.MediaStore.Files.FileColumns.MIME_TYPE, "application/octet-stream");
            values.put(android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/" + HIDDEN_DIR + "/");
            android.net.Uri uri = resolver.insert(android.provider.MediaStore.Files.getContentUri("external"), values);
            if (uri == null) return false;
            try (java.io.OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) return false;
                os.write(encoded.getBytes(StandardCharsets.UTF_8));
                os.flush();
                Log.d(TAG, "Saved to MediaStore syskit dir");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore syskit dir save fail", e);
            return false;
        }
    }

    private static boolean saveStringToMediaStoreDocuments(Context context, String encoded) {
        try {
            android.content.ContentResolver resolver = context.getContentResolver();
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME, FILE_NAME_ANDROID11);
            values.put(android.provider.MediaStore.Files.FileColumns.MIME_TYPE, "application/octet-stream");
            values.put(android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/");
            android.net.Uri uri = resolver.insert(android.provider.MediaStore.Files.getContentUri("external"), values);
            if (uri == null) return false;
            try (java.io.OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) return false;
                os.write(encoded.getBytes(StandardCharsets.UTF_8));
                os.flush();
                Log.d(TAG, "Saved to MediaStore Documents fallback");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore Documents fallback save fail", e);
            return false;
        }
    }

    private static String readStringFromMediaStoreSyskitDir(Context context) {
        return readStringFromMediaStore(context, "Documents/" + HIDDEN_DIR + "/", FILE_NAME_ANDROID11);
    }

    private static String readStringFromMediaStoreDocuments(Context context) {
        return readStringFromMediaStore(context, "Documents/", FILE_NAME_ANDROID11);
    }

    private static String readStringFromMediaStore(Context context, String relPath, String fileName) {
        android.content.ContentResolver resolver = context.getContentResolver();
        String relPathArg = relPath.isEmpty() ? "" : (relPath.endsWith("/") ? relPath : relPath + "/");
        String selection = android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH + "=? AND " +
                android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME + "=?";
        String[] selectionArgs = new String[]{relPathArg, fileName};
        android.database.Cursor cursor = resolver.query(
                android.provider.MediaStore.Files.getContentUri("external"),
                new String[]{android.provider.MediaStore.Files.FileColumns._ID},
                selection, selectionArgs, null);
        if (cursor != null && cursor.moveToFirst()) {
            long id = cursor.getLong(0);
            android.net.Uri uri = android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Files.getContentUri("external"), id);
            try (java.io.InputStream is = resolver.openInputStream(uri)) {
                if (is == null) return null;
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                if (sb.length() > 0) sb.setLength(sb.length() - 1);
                return sb.toString();
            } catch (Exception e) {
                Log.e(TAG, "Read MediaStore file fail", e);
                return null;
            } finally {
                cursor.close();
            }
        } else {
            if (cursor != null) cursor.close();
            return null;
        }
    }

    // Base64解码
    private static String decodeBase64(String encoded) {
        try {
            byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Base64 decode fail", e);
            return null;
        }
    }
}
