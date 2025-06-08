package com.example.androidkit;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "wzy-MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private EditText inputBox;
    private Button sendButton;
    private Button saveButton;
    private Button loadButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputBox = findViewById(R.id.inputBox);
        sendButton = findViewById(R.id.sendButton);
        saveButton = findViewById(R.id.saveButton);
        loadButton = findViewById(R.id.loadButton);

        sendButton.setOnClickListener(v -> {
            // 可按需实现发送逻辑
        });

        saveButton.setOnClickListener(v -> {
            String textToSave = inputBox.getText().toString().trim();
            if (textToSave.isEmpty()) {
                Toast.makeText(this, "请输入要保存的文本", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean success = ExternalStorageUtils.saveStringToExternalStorage(this, textToSave);
            if (success) {
                Toast.makeText(this, "文本已成功保存到外部存储", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "保存失败，请检查日志", Toast.LENGTH_SHORT).show();
            }
        });

        loadButton.setOnClickListener(v -> {
            String loadedText = ExternalStorageUtils.readStringFromExternalStorage(this);
            if (loadedText != null) {
                inputBox.setText(loadedText);
                Toast.makeText(this, "文本已成功从外部存储加载", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "加载失败，可能是文件不存在或读取错误", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 检查并请求必要的权限
     * @return 如果已有权限返回true，否则返回false
     */
    private boolean checkAndRequestPermissions() {
        // Android 10 (API 29)及以上版本不需要请求外部存储权限
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                Manifest.permission.READ_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_CODE);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "权限已授予，可以使用外部存储", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "权限被拒绝，无法使用外部存储", Toast.LENGTH_SHORT).show();
            }
        }
    }
}