package com.example.androidkit;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理外部存储的工具类，用于在应用卸载后仍能保留数据
 * 适配Android各版本的存储权限变更
 * 优化后可以解决应用卸载重装后UID变化导致无法访问文件的问题
 */
public class ExternalStorageUtils {
    private static final String TAG = "wzy-ExternalStorage";
    private static final String HIDDEN_DIR = "Android/syskit"; // 所有版本统一目录
    private static final String FILE_NAME_ANDROID10 = ".sysdata"; // Android 10及以下
    private static final String FILE_NAME_ANDROID11 = "sysdata.txt"; // Android 11+，使用.txt后缀
    
    // 使用公共MIME类型
    private static final String PUBLIC_MIME_TYPE = "text/plain";

    // 存储文件URI，用于权限请求
    private static Uri lastSavedFileUri = null;
    
    // 保存SAF URI
    private static Uri safUri = null;

    /**
     * 保存字符串到外部存储，兼容所有Android主流版本，内容Base64编码
     * 优化后可以解决应用卸载重装后UID变化导致无法访问文件的问题
     */
    public static boolean saveStringToExternalStorage(Context context, String data) {
        if (data == null) {
            Log.e(TAG, "Cannot save null data");
            return false;
        }
        
        Log.d(TAG, "准备保存数据，长度: " + data.length() + " 字符");
        
        String encoded = Base64.encodeToString(data.getBytes(StandardCharsets.UTF_8), Base64.DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+，使用MediaStore API
            Log.d(TAG, "当前Android版本: " + Build.VERSION.SDK_INT + "，使用MediaStore API保存数据");
            return saveStringToMediaStore(context, encoded);
        } else {
            // Android 9及以下，直接写入根目录/Android/syskit/.sysdata
            Log.d(TAG, "当前Android版本: " + Build.VERSION.SDK_INT + "，使用直接文件访问保存数据");
            
            File externalStorage = Environment.getExternalStorageDirectory();
            Log.d(TAG, "外部存储根目录: " + externalStorage.getAbsolutePath());
            
            File dir = new File(externalStorage, HIDDEN_DIR);
            Log.d(TAG, "目标目录: " + dir.getAbsolutePath() + "，是否存在: " + dir.exists());
            
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "创建目录失败: " + dir.getAbsolutePath());
                return false;
            }
            
            File file = new File(dir, FILE_NAME_ANDROID10);
            Log.d(TAG, "目标文件: " + file.getAbsolutePath());
            
            try (FileOutputStream fos = new FileOutputStream(file);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(encoded);
                writer.flush();
                Log.d(TAG, "数据成功保存到文件: " + file.getAbsolutePath() + "，文件大小: " + file.length() + " 字节");
                return true;
            } catch (IOException e) {
                Log.e(TAG, "保存到隐藏目录失败: " + file.getAbsolutePath(), e);
                return false;
            }
        }
    }

    /**
     * 保存字符串到MediaStore公共目录
     * 使用公共MIME类型和公共目录，解决应用卸载重装后UID变化的问题
     */
    private static boolean saveStringToMediaStore(Context context, String data) {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        
        // 使用公共下载目录和公共MIME类型
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME_ANDROID11);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+，设置相对路径
            String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + HIDDEN_DIR;
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            Log.d(TAG, "设置相对路径: " + relativePath);
        }
        
        Uri uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL);
        Uri fileUri = null;
        
        try {
            // 先尝试查询是否已存在同名文件
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
            String[] selectionArgs = new String[]{FILE_NAME_ANDROID11};
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection += " AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
                String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + HIDDEN_DIR + "/";
                selectionArgs = new String[]{FILE_NAME_ANDROID11, relativePath};
            }
            
            try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.MediaColumns._ID}, 
                    selection, selectionArgs, null)) {
                
                if (cursor != null && cursor.moveToFirst()) {
                    // 文件已存在，获取其URI
                    long id = cursor.getLong(0);
                    fileUri = ContentUris.withAppendedId(uri, id);
                    Log.d(TAG, "找到已存在的文件，URI: " + fileUri);
                } else {
                    // 文件不存在，创建新文件
                    fileUri = resolver.insert(uri, values);
                    Log.d(TAG, "创建新文件，URI: " + fileUri);
                }
            }
            
            if (fileUri == null) {
                Log.e(TAG, "无法创建或找到文件");
                return false;
            }
            
            // 保存URI用于权限请求
            lastSavedFileUri = fileUri;
            
            // 获取文件的实际路径
            String filePath = getPathFromUri(context, fileUri);
            if (filePath != null) {
                Log.d(TAG, "文件实际路径: " + filePath);
            } else {
                Log.d(TAG, "无法获取文件实际路径，只有URI: " + fileUri);
            }
            
            try (OutputStream os = resolver.openOutputStream(fileUri, "wt")) {
                if (os == null) {
                    Log.e(TAG, "无法打开输出流，URI: " + fileUri);
                    return false;
                }
                os.write(data.getBytes(StandardCharsets.UTF_8));
                os.flush();
                Log.d(TAG, "成功写入数据到MediaStore，长度: " + data.length() + " 字符");
                return true;
            } catch (IOException e) {
                Log.e(TAG, "写入MediaStore失败，URI: " + fileUri, e);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "保存到MediaStore时发生异常", e);
            return false;
        }
    }

    /**
     * 获取需要请求权限的URI列表
     * 用于MediaStore.createWriteRequest
     */
    public static List<Uri> getPendingPermissionUris() {
        List<Uri> uris = new ArrayList<>();
        if (lastSavedFileUri != null) {
            try {
                // 确保URI是媒体项目URI
                String uriString = lastSavedFileUri.toString();
                Log.d(TAG, "检查URI是否为媒体项目: " + uriString);
                
                // 只接受MediaStore的URI
                if (uriString.startsWith("content://media/")) {
                    // 确保URI指向Downloads目录下的媒体项目
                    if (uriString.contains("external") && uriString.contains("downloads")) {
                        Log.d(TAG, "添加有效的媒体URI到权限请求列表: " + lastSavedFileUri);
                        uris.add(lastSavedFileUri);
                    } else {
                        Log.e(TAG, "URI不是下载目录中的媒体项目: " + lastSavedFileUri);
                    }
                } else {
                    Log.e(TAG, "URI不是媒体项目: " + lastSavedFileUri);
                }
            } catch (Exception e) {
                Log.e(TAG, "处理URI时发生异常", e);
            }
        }
        return uris;
    }

    /**
     * 尝试直接读取已知路径的文件
     * 当MediaStore查询失败时使用，如果文件存在但没有权限会抛出SecurityException
     */
    private static String tryReadExistingFile(Context context) throws SecurityException {
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File targetDir = new File(downloadDir, HIDDEN_DIR);
            File targetFile = new File(targetDir, FILE_NAME_ANDROID11);
            
            Log.d(TAG, "尝试直接读取已知文件: " + targetFile.getAbsolutePath() + "，文件是否存在: " + targetFile.exists());
            
            if (!targetFile.exists()) {
                Log.e(TAG, "文件不存在: " + targetFile.getAbsolutePath());
                return null;
            }
            
            // 先尝试扫描文件，确保MediaStore能识别到它
            scanMediaFile(context);
            
            // 尝试获取文件的URI
            Uri fileUri = getUriForFile(context, targetFile);
            if (fileUri != null) {
                Log.d(TAG, "获取到文件URI: " + fileUri);
                lastSavedFileUri = fileUri; // 保存URI用于权限请求
            } else {
                Log.d(TAG, "无法获取文件URI，但仍将尝试直接读取");
            }
            
            try {
                // 尝试直接读取文件
                try (FileInputStream fis = new FileInputStream(targetFile);
                     InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
                     BufferedReader reader = new BufferedReader(isr)) {
                    
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    if (sb.length() > 0) sb.setLength(sb.length() - 1);
                    
                    Log.d(TAG, "成功直接读取文件，文件大小: " + targetFile.length() + " 字节，内容长度: " + sb.length() + " 字符");
                    return sb.toString();
                }
            } catch (IOException e) {
                Log.e(TAG, "直接读取文件失败: " + e.getMessage(), e);
                
                // 检查是否是权限问题
                if (e.getCause() instanceof SecurityException || e.getMessage() != null && e.getMessage().contains("Permission denied")) {
                    Log.e(TAG, "读取文件失败，权限被拒绝", e);
                    
                    // 如果是权限问题，抛出SecurityException
                    throw new SecurityException("无法访问文件，需要权限: " + targetFile.getAbsolutePath(), e);
                }
                
                // 其他IO错误
                return null;
            }
        } catch (SecurityException e) {
            // 直接重新抛出安全异常
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "尝试读取已知文件时发生异常", e);
            return null;
        }
    }

    /**
     * 获取文件的MediaStore URI
     */
    private static Uri getUriForFile(Context context, File file) {
        try {
            // 先尝试通过MediaStore查询
            ContentResolver resolver = context.getContentResolver();
            
            // 确保使用正确的媒体URI
            Uri queryUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL);
            Log.d(TAG, "使用媒体URI查询: " + queryUri);
            
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
            String[] selectionArgs = new String[]{FILE_NAME_ANDROID11};
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " +
                        MediaStore.MediaColumns.RELATIVE_PATH + "=?";
                String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + HIDDEN_DIR + "/";
                selectionArgs = new String[]{FILE_NAME_ANDROID11, relativePath};
            }
            
            try (Cursor cursor = resolver.query(
                    queryUri,
                    new String[]{MediaStore.MediaColumns._ID},
                    selection, selectionArgs, null)) {
                
                if (cursor != null && cursor.moveToFirst()) {
                    long id = cursor.getLong(0);
                    Uri uri = ContentUris.withAppendedId(queryUri, id);
                    Log.d(TAG, "找到媒体项目URI: " + uri);
                    return uri;
                }
            }
            
            // 如果查询失败，尝试创建媒体项目
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "尝试创建新的媒体项目");
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME_ANDROID11);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + HIDDEN_DIR);
                
                Uri uri = resolver.insert(queryUri, values);
                if (uri != null) {
                    Log.d(TAG, "成功创建媒体项目: " + uri);
                    
                    // 写入一些默认内容
                    try (OutputStream os = resolver.openOutputStream(uri)) {
                        if (os != null) {
                            String defaultContent = encodeBase64("这是一个默认的测试内容");
                            os.write(defaultContent.getBytes(StandardCharsets.UTF_8));
                            Log.d(TAG, "写入默认内容到新创建的媒体项目");
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "写入媒体项目失败", e);
                    }
                    
                    return uri;
                }
            }
            
            Log.d(TAG, "未找到文件的MediaStore记录，无法获取有效URI用于权限请求");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "获取文件URI失败", e);
            return null;
        }
    }

    /**
     * 从MediaStore公共目录读取字符串
     * 使用公共MIME类型和公共目录，解决应用卸载重装后UID变化的问题
     */
    private static String readStringFromMediaStore(Context context) {
        ContentResolver resolver = context.getContentResolver();
        
        // 查询下载目录中的指定文件
        String selection;
        String[] selectionArgs;
        
        // 确保使用正确的媒体URI
        Uri queryUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL);
        Log.d(TAG, "使用媒体URI查询: " + queryUri);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 修改查询条件，去掉通配符，使用精确路径匹配
            selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " +
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?";
            String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + HIDDEN_DIR;
            selectionArgs = new String[]{relativePath + "/", FILE_NAME_ANDROID11};
            Log.d(TAG, "查询条件: 相对路径=" + relativePath + "/ 且 文件名=" + FILE_NAME_ANDROID11);
        } else {
            selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
            selectionArgs = new String[]{FILE_NAME_ANDROID11};
            Log.d(TAG, "查询条件: 文件名=" + FILE_NAME_ANDROID11);
        }
        
        Log.d(TAG, "查询URI: " + queryUri);
        
        // 尝试先扫描下载目录，确保MediaStore能识别到文件
        scanMediaFile(context);
        
        try (Cursor cursor = resolver.query(
                queryUri,
                new String[]{MediaStore.MediaColumns._ID},
                selection, selectionArgs, null)) {
            
            if (cursor == null) {
                Log.e(TAG, "查询返回空Cursor");
                return null;
            }
            
            Log.d(TAG, "查询结果数量: " + cursor.getCount());
            
            if (cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                Uri uri = ContentUris.withAppendedId(queryUri, id);
                Log.d(TAG, "找到文件，URI: " + uri);
                
                // 保存URI用于权限请求
                lastSavedFileUri = uri;
                
                // 获取文件的实际路径
                String filePath = getPathFromUri(context, uri);
                if (filePath != null) {
                    Log.d(TAG, "文件实际路径: " + filePath);
                } else {
                    Log.d(TAG, "无法获取文件实际路径，只有URI: " + uri);
                }
                
                try (InputStream is = resolver.openInputStream(uri)) {
                    if (is == null) {
                        Log.e(TAG, "无法打开输入流，URI: " + uri);
                        return null;
                    }
                    
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    if (sb.length() > 0) sb.setLength(sb.length() - 1);
                    
                    Log.d(TAG, "成功从MediaStore读取数据，内容长度: " + sb.length() + " 字符");
                    return sb.toString();
                } catch (IOException e) {
                    Log.e(TAG, "读取MediaStore失败，URI: " + uri, e);
                    return null;
                } catch (SecurityException e) {
                    Log.e(TAG, "读取MediaStore时发生安全异常，可能需要请求权限", e);
                    return null;
                }
            } else {
                Log.d(TAG, "未找到匹配的文件");
                
                // MediaStore查询失败，尝试直接读取已知文件
                Log.d(TAG, "尝试直接读取已知路径的文件");
                String result = tryReadExistingFile(context);
                if (result != null) {
                    Log.d(TAG, "成功直接读取已知文件");
                    return result;
                }
                
                // 尝试创建新的媒体项目
                Uri uri = getUriForFile(context, new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), HIDDEN_DIR + "/" + FILE_NAME_ANDROID11));
                if (uri != null) {
                    Log.d(TAG, "成功创建媒体项目，URI: " + uri);
                    lastSavedFileUri = uri;
                    
                    // 尝试读取新创建的媒体项目
                    try (InputStream is = resolver.openInputStream(uri)) {
                        if (is != null) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                sb.append(line).append("\n");
                            }
                            if (sb.length() > 0) sb.setLength(sb.length() - 1);
                            
                            Log.d(TAG, "成功从新创建的媒体项目读取数据，内容长度: " + sb.length() + " 字符");
                            return sb.toString();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "读取新创建的媒体项目失败", e);
                    }
                }
                
                // 列出所有下载目录中的文件，帮助调试
                listAllDownloadFiles(context);
                
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "查询MediaStore时发生异常", e);
            
            // 出现异常时，也尝试直接读取已知文件
            Log.d(TAG, "查询异常，尝试直接读取已知路径的文件");
            String result = tryReadExistingFile(context);
            if (result != null) {
                Log.d(TAG, "成功直接读取已知文件");
                return result;
            }
            
            return null;
        }
    }

    /**
     * 读取外部存储的字符串，自动Base64解码，兼容所有Android主流版本
     * 优化后可以解决应用卸载重装后UID变化导致无法访问文件的问题
     * 如果因权限问题无法访问文件，会抛出SecurityException
     */
    public static String readStringFromExternalStorage(Context context) throws SecurityException {
        Log.d(TAG, "开始从外部存储读取数据");
        Log.d(TAG, "当前Android版本: " + Build.VERSION.SDK_INT);
        
        // 首先尝试使用SAF URI读取
        if (safUri != null) {
            Log.d(TAG, "尝试使用已保存的SAF URI读取文件: " + safUri);
            String content = readStringFromSafUri(context, safUri);
            if (content != null) {
                Log.d(TAG, "成功从SAF URI读取文件内容");
                return content;
            }
            Log.d(TAG, "从SAF URI读取失败，尝试其他方法");
        }
        
        String encoded = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+，使用MediaStore API
            Log.d(TAG, "使用MediaStore API读取数据");
            try {
                encoded = readStringFromMediaStore(context);
                if (encoded != null) {
                    Log.d(TAG, "从MediaStore成功读取数据，长度: " + encoded.length() + " 字符");
                } else {
                    Log.e(TAG, "从MediaStore读取数据失败，返回null");
                    
                    // 如果MediaStore读取失败，尝试直接读取已知文件
                    Log.d(TAG, "MediaStore读取失败，尝试直接读取已知路径的文件");
                    encoded = tryReadExistingFile(context);
                    if (encoded != null) {
                        Log.d(TAG, "成功直接读取已知文件");
                    }
                }
            } catch (SecurityException e) {
                Log.e(TAG, "读取文件时发生安全异常，需要请求权限", e);
                // 重新抛出异常，让MainActivity处理
                throw e;
            }
            return encoded == null ? null : decodeBase64(encoded);
        } else {
            // Android 9及以下，直接读取文件
            File externalStorage = Environment.getExternalStorageDirectory();
            Log.d(TAG, "外部存储根目录: " + externalStorage.getAbsolutePath());
            
            File dir = new File(externalStorage, HIDDEN_DIR);
            File file = new File(dir, FILE_NAME_ANDROID10);
            
            Log.d(TAG, "尝试读取文件: " + file.getAbsolutePath() + "，文件是否存在: " + file.exists());
            
            if (!file.exists()) {
                Log.e(TAG, "文件不存在: " + file.getAbsolutePath());
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
                
                Log.d(TAG, "成功从文件读取数据，文件大小: " + file.length() + " 字节，读取内容长度: " + sb.length() + " 字符");
                
                return decodeBase64(sb.toString());
            } catch (IOException e) {
                Log.e(TAG, "读取文件失败: " + file.getAbsolutePath(), e);
                return null;
            }
        }
    }

    /**
     * 尝试扫描媒体文件，确保MediaStore能识别到文件
     * 特别是应用卸载重装后，需要重新扫描
     * 此方法设为public，可以从MainActivity调用
     */
    public static void scanMediaFile(Context context) {
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File targetDir = new File(downloadDir, HIDDEN_DIR);
            File targetFile = new File(targetDir, FILE_NAME_ANDROID11);
            
            Log.d(TAG, "尝试扫描文件: " + targetFile.getAbsolutePath() + "，文件是否存在: " + targetFile.exists());
            
            // 如果文件不存在，尝试创建一个空文件
            if (!targetFile.exists()) {
                try {
                    if (!targetDir.exists()) {
                        targetDir.mkdirs();
                    }
                    
                    // 创建一个空文件，确保有东西可以扫描
                    if (targetFile.createNewFile()) {
                        Log.d(TAG, "创建了空文件: " + targetFile.getAbsolutePath());
                        
                        // 写入一些默认内容
                        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                            String defaultContent = encodeBase64("这是一个默认的测试内容");
                            fos.write(defaultContent.getBytes(StandardCharsets.UTF_8));
                            Log.d(TAG, "写入默认内容到新创建的文件");
                        } catch (IOException e) {
                            Log.e(TAG, "写入默认内容失败", e);
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "创建文件失败", e);
                }
            }
            
            if (targetFile.exists()) {
                MediaScannerConnection.scanFile(
                    context,
                    new String[] { targetFile.getAbsolutePath() },
                    new String[] { "text/plain" },
                    (path, uri) -> {
                        if (uri != null) {
                            Log.d(TAG, "媒体扫描完成，路径: " + path + "，URI: " + uri);
                            // 保存扫描后的URI用于权限请求
                            lastSavedFileUri = uri;
                            
                            // 尝试通过MediaStore查询获取正确的媒体URI
                            Uri mediaUri = getUriForFile(context, targetFile);
                            if (mediaUri != null) {
                                Log.d(TAG, "获取到媒体URI: " + mediaUri);
                                lastSavedFileUri = mediaUri;
                            }
                        } else {
                            Log.e(TAG, "媒体扫描失败，路径: " + path);
                        }
                    }
                );
            } else {
                Log.d(TAG, "扫描目标文件不存在: " + targetFile.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "扫描媒体文件时发生异常", e);
        }
    }

    /**
     * 列出下载目录中的所有文件，用于调试
     */
    private static void listAllDownloadFiles(Context context) {
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            
            Log.d(TAG, "列出所有下载目录文件 - 开始");
            
            try (Cursor cursor = resolver.query(
                    queryUri,
                    new String[]{
                            MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            MediaStore.MediaColumns.SIZE
                    },
                    null, null, null)) {
                
                if (cursor != null && cursor.getCount() > 0) {
                    Log.d(TAG, "下载目录中共有 " + cursor.getCount() + " 个文件");
                    
                    int idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
                    int nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                    int pathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
                    int sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
                    
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idColumn);
                        String name = cursor.getString(nameColumn);
                        String path = pathColumn >= 0 ? cursor.getString(pathColumn) : "N/A";
                        long size = sizeColumn >= 0 ? cursor.getLong(sizeColumn) : -1;
                        
                        Uri uri = ContentUris.withAppendedId(queryUri, id);
                        String realPath = getPathFromUri(context, uri);
                        
                        Log.d(TAG, "文件: ID=" + id + ", 名称=" + name + 
                                ", 相对路径=" + path + ", 大小=" + size + " 字节" +
                                ", 实际路径=" + (realPath != null ? realPath : "未知"));
                    }
                } else {
                    Log.d(TAG, "下载目录为空");
                }
            }
            
            Log.d(TAG, "列出所有下载目录文件 - 结束");
        } catch (Exception e) {
            Log.e(TAG, "列出下载文件时发生异常", e);
        }
    }
    
    /**
     * 尝试从Uri获取实际文件路径
     */
    private static String getPathFromUri(Context context, Uri uri) {
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                Log.d(TAG, "URI是Document类型");
                return null; // 需要更复杂的处理
            }
            
            String[] projection = {MediaStore.MediaColumns.DATA};
            Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
                String path = cursor.getString(columnIndex);
                cursor.close();
                return path;
            } else if (cursor != null) {
                cursor.close();
            }
            
            // 尝试直接从URI获取路径
            String path = uri.getPath();
            if (path != null && path.startsWith("/external/")) {
                path = "/storage" + path.substring(9);
                return path;
            }
            
            return uri.getPath();
        } catch (Exception e) {
            Log.e(TAG, "获取文件路径失败", e);
            return null;
        }
    }

    /**
     * 设置SAF URI
     */
    public static void setSafUri(Uri uri) {
        safUri = uri;
        Log.d(TAG, "设置SAF URI: " + uri);
    }
    
    /**
     * 获取SAF URI
     */
    public static Uri getSafUri() {
        return safUri;
    }
    
    /**
     * 通过SAF URI读取文件内容
     */
    public static String readStringFromSafUri(Context context, Uri uri) {
        Log.d(TAG, "从SAF URI读取内容: " + uri);
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                Log.e(TAG, "无法打开输入流");
                return null;
            }
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }
            if (stringBuilder.length() > 0) {
                stringBuilder.setLength(stringBuilder.length() - 1);
            }
            reader.close();
            inputStream.close();
            
            String content = stringBuilder.toString();
            Log.d(TAG, "成功从SAF URI读取内容，长度: " + content.length() + " 字符");
            
            // 尝试Base64解码
            try {
                return decodeBase64(content);
            } catch (Exception e) {
                Log.w(TAG, "内容不是Base64编码，返回原始内容");
                return content;
            }
        } catch (Exception e) {
            Log.e(TAG, "从SAF URI读取内容失败", e);
            return null;
        }
    }
    
    /**
     * 通过SAF URI写入文件内容
     */
    public static boolean writeStringToSafUri(Context context, Uri uri, String content) {
        Log.d(TAG, "写入内容到SAF URI: " + uri);
        try {
            // Base64编码内容
            String encodedContent = encodeBase64(content);
            
            OutputStream outputStream = context.getContentResolver().openOutputStream(uri, "wt");
            if (outputStream == null) {
                Log.e(TAG, "无法打开输出流");
                return false;
            }
            
            OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            writer.write(encodedContent);
            writer.flush();
            writer.close();
            outputStream.close();
            
            Log.d(TAG, "成功写入内容到SAF URI，长度: " + encodedContent.length() + " 字符");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "写入内容到SAF URI失败", e);
            return false;
        }
    }

    // Base64解码
    private static String decodeBase64(String encoded) {
        try {
            byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
            String result = new String(decoded, StandardCharsets.UTF_8);
            Log.d(TAG, "Base64解码成功，解码前长度: " + encoded.length() + "，解码后长度: " + result.length());
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Base64解码失败", e);
            return null;
        }
    }
    
    // Base64编码
    private static String encodeBase64(String data) {
        try {
            byte[] encoded = Base64.encode(data.getBytes(StandardCharsets.UTF_8), Base64.DEFAULT);
            String result = new String(encoded, StandardCharsets.UTF_8);
            Log.d(TAG, "Base64编码成功，编码前长度: " + data.length() + "，编码后长度: " + result.length());
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Base64编码失败", e);
            return null;
        }
    }
}
