package com.example.androidkit;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
    
    // 保存当前操作类型，用于权限请求后的回调
    private enum OperationType { NONE, SAVE, LOAD }
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
        boolean success = ExternalStorageUtils.saveStringToExternalStorage(this, text);
        if (success) {
            Toast.makeText(this, "文本已成功保存到外部存储", Toast.LENGTH_SHORT).show();
        } else {
            // 如果保存失败，可能是权限问题，尝试请求权限
            requestWritePermissionIfNeeded();
        }
    }
    
    /**
     * 从外部存储加载文本
     */
    private void loadText() {
        String loadedText = ExternalStorageUtils.readStringFromExternalStorage(this);
        if (loadedText != null) {
            inputBox.setText(loadedText);
            Toast.makeText(this, "文本已成功从外部存储加载", Toast.LENGTH_SHORT).show();
        } else {
            // 如果加载失败，可能是权限问题，尝试请求权限
            requestWritePermissionIfNeeded();
        }
    }
    
    /**
     * 如果需要，请求文件写入权限
     */
    private void requestWritePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            List<Uri> uris = ExternalStorageUtils.getPendingPermissionUris();
            if (uris.isEmpty()) {
                Log.d(TAG, "没有需要请求权限的URI");
                Toast.makeText(this, "操作失败，无法找到目标文件", Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                Log.d(TAG, "请求文件访问权限，URI数量: " + uris.size());
                IntentSender sender = MediaStore.createWriteRequest(getContentResolver(), uris).getIntentSender();
                IntentSenderRequest request = new IntentSenderRequest.Builder(sender).build();
                writePermissionLauncher.launch(request);
            } catch (Exception e) {
                Log.e(TAG, "请求文件权限失败", e);
                Toast.makeText(this, "请求文件权限失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
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
}