package com.example.androidkit

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * 管理外部存储的工具类，用于在应用卸载后仍能保留数据
 * 适配Android各版本的存储权限变更
 */
object ExternalStorageManager {
    private const val TAG = "ExternalStorageManager"
    private const val HIDDEN_DIR = "Android/syskit" // 所有版本统一目录
    private const val FILE_NAME_ANDROID10 = ".sysdata" // Android 10及以下
    private const val FILE_NAME_ANDROID11 = "sysdata" // Android 11+

    /**
     * 保存字符串到外部存储，兼容所有Android主流版本，内容Base64编码，路径高度隐蔽
     */
    fun saveStringToExternalStorage(context: Context, data: String?): Boolean {
        if (data == null) {
            Log.e(TAG, "Cannot save null data")
            return false
        }
        val encoded = Base64.encodeToString(data.toByteArray(StandardCharsets.UTF_8), Base64.DEFAULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+，用MediaStore写入Documents/Android/syskit/sysdata
            val ok = saveStringToMediaStoreSyskitDir(context, encoded)
            if (ok) return true
            // 回退普通Documents目录
            return saveStringToMediaStoreDocuments(context, encoded)
        } else {
            // Android 10及以下，直接写入根目录/Android/syskit/.sysdata
            val dir = File(Environment.getExternalStorageDirectory(), HIDDEN_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Failed to create hidden dir: ${dir.absolutePath}")
                return false
            }
            val file = File(dir, FILE_NAME_ANDROID10)
            return try {
                FileOutputStream(file).use { fos ->
                    OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                        writer.write(encoded)
                        writer.flush()
                        Log.d(TAG, "Saved to hidden dir: ${file.absolutePath}")
                        true
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error saving to hidden dir", e)
                false
            }
        }
    }

    /**
     * 读取外部存储的字符串，自动Base64解码，兼容所有Android主流版本
     */
    fun readStringFromExternalStorage(context: Context): String? {
        var encoded: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+，查Documents/Android/syskit/sysdata，否则查Documents/sysdata
            encoded = readStringFromMediaStoreSyskitDir(context)
            if (encoded != null) return decodeBase64(encoded)
            encoded = readStringFromMediaStoreDocuments(context)
            return encoded?.let { decodeBase64(it) }
        } else {
            val dir = File(Environment.getExternalStorageDirectory(), HIDDEN_DIR)
            val file = File(dir, FILE_NAME_ANDROID10)
            if (!file.exists()) {
                Log.e(TAG, "Hidden file does not exist: ${file.absolutePath}")
                return null
            }
            return try {
                FileInputStream(file).use { fis ->
                    InputStreamReader(fis, StandardCharsets.UTF_8).use { isr ->
                        BufferedReader(isr).use { reader ->
                            val sb = StringBuilder()
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                sb.append(line).append("\n")
                            }
                            if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
                            decodeBase64(sb.toString())
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error reading from hidden dir", e)
                null
            }
        }
    }

    // Android 11+专用 MediaStore 写入/读取
    private fun saveStringToMediaStoreSyskitDir(context: Context, encoded: String): Boolean {
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, FILE_NAME_ANDROID11)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/$HIDDEN_DIR/")
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return false
            resolver.openOutputStream(uri)?.use { os ->
                os.write(encoded.toByteArray(StandardCharsets.UTF_8))
                os.flush()
                Log.d(TAG, "Saved to MediaStore syskit dir")
                true
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore syskit dir save fail", e)
            false
        }
    }

    private fun saveStringToMediaStoreDocuments(context: Context, encoded: String): Boolean {
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, FILE_NAME_ANDROID11)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/")
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return false
            resolver.openOutputStream(uri)?.use { os ->
                os.write(encoded.toByteArray(StandardCharsets.UTF_8))
                os.flush()
                Log.d(TAG, "Saved to MediaStore Documents fallback")
                true
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore Documents fallback save fail", e)
            false
        }
    }

    private fun readStringFromMediaStoreSyskitDir(context: Context): String? {
        return readStringFromMediaStore(context, "Documents/$HIDDEN_DIR/", FILE_NAME_ANDROID11)
    }

    private fun readStringFromMediaStoreDocuments(context: Context): String? {
        return readStringFromMediaStore(context, "Documents/", FILE_NAME_ANDROID11)
    }

    private fun readStringFromMediaStore(context: Context, relPath: String, fileName: String): String? {
        val resolver = context.contentResolver
        val relPathArg = if (relPath.isEmpty()) "" else if (relPath.endsWith("/")) relPath else "$relPath/"
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME}=?"
        val selectionArgs = arrayOf(relPathArg, fileName)
        val cursor = resolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns._ID),
            selection, selectionArgs, null
        )
        
        return if (cursor != null && cursor.moveToFirst()) {
            val id = cursor.getLong(0)
            val uri = ContentUris.withAppendedId(
                MediaStore.Files.getContentUri("external"), id
            )
            try {
                resolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                        val sb = StringBuilder()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            sb.append(line).append("\n")
                        }
                        if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
                        sb.toString()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read MediaStore file fail", e)
                null
            } finally {
                cursor.close()
            }
        } else {
            cursor?.close()
            null
        }
    }

    // Base64解码
    private fun decodeBase64(encoded: String): String? {
        return try {
            val decoded = Base64.decode(encoded, Base64.DEFAULT)
            String(decoded, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Base64 decode fail", e)
            null
        }
    }
}
