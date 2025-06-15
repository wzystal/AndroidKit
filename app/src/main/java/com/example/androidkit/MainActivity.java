package com.example.androidkit;

import android.Manifest;
import android.app.Activity;
import android.app.RecoverableSecurityException;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "wzy-MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int WRITE_REQUEST_CODE = 101;

    private EditText inputBox;
    private Button sendButton;
    private Button saveButton;
    private Button loadButton;
    
    // 用于处理MediaStore.createWriteRequest的结果
    private ActivityResultLauncher<IntentSenderRequest> writePermissionLauncher;
    
    // 操作类型枚举
    private enum OperationType {
        NONE,
        SAVE,
        LOAD
    }
    
    // 保存当前操作类型，用于权限请求后的回调
    private OperationType pendingOperation = OperationType.NONE;
    private String pendingSaveText = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputBox = findViewById(R.id.inputBox);
        sendButton = findViewById(R.id.sendButton);
        saveButton = findViewById(R.id.saveButton);
        loadButton = findViewById(R.id.loadButton);

        // 初始化权限请求启动器
        writePermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartIntentSenderForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Log.d(TAG, "用户授予了文件访问权限");
                    // 根据之前的操作类型执行相应操作
                    if (pendingOperation == OperationType.SAVE && pendingSaveText != null) {
                        saveText(pendingSaveText);
                        pendingSaveText = null;
                    } else if (pendingOperation == OperationType.LOAD) {
                        loadText();
                    }
                } else {
                    Log.d(TAG, "用户拒绝了文件访问权限");
                    Toast.makeText(this, "需要文件访问权限才能继续操作", Toast.LENGTH_SHORT).show();
                }
                pendingOperation = OperationType.NONE;
            }
        );

        // 启动时检查权限
        checkAndRequestPermissions();

        sendButton.setOnClickListener(v -> {
            // 可按需实现发送逻辑
        });

        saveButton.setOnClickListener(v -> {
            if (!checkAndRequestPermissions()) {
                Toast.makeText(this, "需要存储权限才能保存文件", Toast.LENGTH_SHORT).show();
                return;
            }
            
            String textToSave = inputBox.getText().toString().trim();
            if (textToSave.isEmpty()) {
                Toast.makeText(this, "请输入要保存的文本", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 保存文本，如果需要权限会触发权限请求
            pendingOperation = OperationType.SAVE;
            pendingSaveText = textToSave;
            saveText(textToSave);
        });

        loadButton.setOnClickListener(v -> {
            if (!checkAndRequestPermissions()) {
                Toast.makeText(this, "需要存储权限才能加载文件", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 加载文本，如果需要权限会触发权限请求
            pendingOperation = OperationType.LOAD;
            loadText();
        });
    }
    
    /**
     * 保存文本到外部存储
     */
    private void saveText(String text) {
        pendingOperation = OperationType.SAVE;
        pendingSaveText = text;
        
        boolean success = ExternalStorageUtils.saveStringToExternalStorage(this, text);
        if (success) {
            Toast.makeText(this, "文本已成功保存到外部存储", Toast.LENGTH_SHORT).show();
            // 重置状态
            pendingOperation = OperationType.NONE;
            pendingSaveText = null;
        } else {
            // 如果保存失败，可能是权限问题，尝试请求权限
            requestWritePermissionIfNeeded();
        }
    }
    
    /**
     * 从外部存储加载文本
     */
    private void loadText() {
        pendingOperation = OperationType.LOAD;
        
        try {
            String loadedText = ExternalStorageUtils.readStringFromExternalStorage(this);
            if (loadedText != null) {
                inputBox.setText(loadedText);
                Toast.makeText(this, "文本已成功从外部存储加载", Toast.LENGTH_SHORT).show();
                // 重置状态
                pendingOperation = OperationType.NONE;
            } else {
                // 如果加载失败，可能是权限问题，尝试请求权限
                Log.d(TAG, "加载文本返回null，尝试请求权限");
                requestWritePermissionIfNeeded();
            }
        } catch (SecurityException e) {
            // 捕获权限异常，直接请求权限
            Log.e(TAG, "加载文本时捕获到SecurityException，直接请求权限", e);
            requestWritePermissionIfNeeded();
        }
    }
    
    /**
     * 如果需要，请求文件写入权限
     */
    private void requestWritePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.d(TAG, "Android 11+，尝试请求文件访问权限");
            
            // 先尝试使用SAF（Storage Access Framework）请求访问权限
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                // 尝试定位到目标文件所在目录
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File targetDir = new File(downloadDir, "Android/syskit");
                    Uri startDir = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download/Android/syskit");
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, startDir);
                }
                Log.d(TAG, "启动SAF请求文件访问权限");
                startActivityForResult(intent, WRITE_REQUEST_CODE);
                return;
            } catch (Exception e) {
                Log.e(TAG, "启动SAF失败", e);
            }
            
            // 如果SAF失败，尝试使用MediaStore API
            // 先尝试扫描媒体文件，确保MediaStore能识别到文件
            ExternalStorageUtils.scanMediaFile(this);
            
            // 等待扫描完成后再请求权限
            new Handler().postDelayed(() -> {
                // 尝试直接读取文件，如果失败则捕获安全异常
                try {
                    String result = ExternalStorageUtils.readStringFromExternalStorage(this);
                    if (result != null) {
                        Log.d(TAG, "成功读取文件，无需请求权限");
                        Toast.makeText(this, "文件读取成功", Toast.LENGTH_SHORT).show();
                        inputBox.setText(result);
                        return;
                    }
                } catch (SecurityException securityException) {
                    Log.e(TAG, "读取文件时发生安全异常，尝试请求权限", securityException);
                    
                    // 从异常中提取RecoverableSecurityException
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && 
                            securityException instanceof RecoverableSecurityException) {
                        try {
                            Log.d(TAG, "检测到RecoverableSecurityException，尝试请求权限");
                            RecoverableSecurityException recoverableSecurityException = 
                                    (RecoverableSecurityException) securityException;
                            IntentSender intentSender = recoverableSecurityException.getUserAction()
                                    .getActionIntent().getIntentSender();
                            IntentSenderRequest request = new IntentSenderRequest.Builder(intentSender).build();
                            writePermissionLauncher.launch(request);
                            return;
                        } catch (Exception e) {
                            Log.e(TAG, "处理RecoverableSecurityException失败", e);
                        }
                    }
                }
                
                // 如果上面的方法失败，尝试使用createWriteRequest
                List<Uri> uris = ExternalStorageUtils.getPendingPermissionUris();
                if (uris.isEmpty()) {
                    Log.d(TAG, "没有需要请求权限的URI");
                    Toast.makeText(this, "操作失败，无法找到目标文件", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                try {
                    Log.d(TAG, "请求文件访问权限，URI数量: " + uris.size());
                    for (Uri uri : uris) {
                        Log.d(TAG, "请求权限的URI: " + uri);
                    }
                    IntentSender sender = MediaStore.createWriteRequest(getContentResolver(), uris).getIntentSender();
                    IntentSenderRequest request = new IntentSenderRequest.Builder(sender).build();
                    writePermissionLauncher.launch(request);
                } catch (Exception e) {
                    Log.e(TAG, "请求文件权限失败", e);
                    Toast.makeText(this, "请求文件权限失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    
                    // 如果createWriteRequest失败，尝试使用ACTION_OPEN_DOCUMENT
                    try {
                        Log.d(TAG, "尝试使用ACTION_OPEN_DOCUMENT请求权限");
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("text/plain");
                        startActivityForResult(intent, WRITE_REQUEST_CODE);
                    } catch (Exception ex) {
                        Log.e(TAG, "启动ACTION_OPEN_DOCUMENT失败", ex);
                        Toast.makeText(this, "无法请求文件权限，请尝试重新安装应用", Toast.LENGTH_LONG).show();
                    }
                }
            }, 1000); // 给媒体扫描一些时间
        }
    }

    /**
     * 检查并请求必要的权限
     * @return 如果已有权限返回true，否则返回false
     */
    private boolean checkAndRequestPermissions() {
        // Android 10 (API 29)及以上版本使用MediaStore API，不需要特殊权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true;
        } 
        // Android 9 (API 28)及以下版本需要READ/WRITE_EXTERNAL_STORAGE权限
        else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                Manifest.permission.READ_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_CODE);
                return false;
            }
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "权限已授予，可以使用外部存储", Toast.LENGTH_SHORT).show();
                // 如果有挂起的操作，继续执行
                if (pendingOperation == OperationType.SAVE && pendingSaveText != null) {
                    saveText(pendingSaveText);
                } else if (pendingOperation == OperationType.LOAD) {
                    loadText();
                }
            } else {
                Toast.makeText(this, "权限被拒绝，无法使用外部存储", Toast.LENGTH_SHORT).show();
            }
            pendingOperation = OperationType.NONE;
            pendingSaveText = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == WRITE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri uri = data.getData();
                Log.d(TAG, "获取到SAF文件访问权限，URI: " + uri);
                
                // 获取持久化权限
                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(uri, takeFlags);
                
                // 保存URI供后续使用
                ExternalStorageUtils.setSafUri(uri);
                
                // 根据之前的操作类型执行相应操作
                if (pendingOperation == OperationType.SAVE && pendingSaveText != null) {
                    // 如果是保存操作，使用SAF URI保存内容
                    boolean success = ExternalStorageUtils.writeStringToSafUri(this, uri, pendingSaveText);
                    if (success) {
                        Toast.makeText(this, "文本已成功保存到外部存储", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
                    }
                    // 重置状态
                    pendingOperation = OperationType.NONE;
                    pendingSaveText = null;
                } else if (pendingOperation == OperationType.LOAD) {
                    // 如果是加载操作，从SAF URI读取内容
                    String content = ExternalStorageUtils.readStringFromSafUri(this, uri);
                    if (content != null) {
                        inputBox.setText(content);
                        Toast.makeText(this, "文本已成功从外部存储加载", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show();
                    }
                    // 重置状态
                    pendingOperation = OperationType.NONE;
                }
            }
        }
    }
}