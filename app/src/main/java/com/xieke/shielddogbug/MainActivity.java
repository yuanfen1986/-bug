package com.xieke.shielddogbug;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private CardView cardNormal, cardShizuku;

    private static final int SHIZUKU_REQUEST_CODE = 1001;

    private final ActivityResultLauncher<Intent> overlayPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (Settings.canDrawOverlays(this)) {
                    startFloatingService(FloatingToggleService.MODE_NORMAL);
                } else {
                    Toast.makeText(this, R.string.toast_no_overlay, Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<Intent> overlayPermissionLauncherShizuku =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (Settings.canDrawOverlays(this)) {
                    requestShizukuPermission();
                } else {
                    Toast.makeText(this, R.string.toast_no_overlay, Toast.LENGTH_SHORT).show();
                }
            });

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_REQUEST_CODE) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        startFloatingService(FloatingToggleService.MODE_SHIZUKU);
                    } else {
                        Toast.makeText(this, "需要授予 Shizuku 权限才能使用此模式", Toast.LENGTH_LONG).show();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cardNormal = findViewById(R.id.card_normal);
        cardShizuku = findViewById(R.id.card_shizuku);

        cardNormal.setOnClickListener(v -> onNormalModeClick());
        cardShizuku.setOnClickListener(v -> onShizukuModeClick());

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
    }

    // ====== 普通模式 ======

    private void onNormalModeClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                startFloatingService(FloatingToggleService.MODE_NORMAL);
            } else {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                overlayPermissionLauncher.launch(intent);
            }
        } else {
            startFloatingService(FloatingToggleService.MODE_NORMAL);
        }
    }

    // ====== Shizuku 模式 ======

    private void onShizukuModeClick() {
        // 第1步：检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            overlayPermissionLauncherShizuku.launch(intent);
            return;
        }

        // 第2步：检查 Shizuku 服务是否在运行
        if (!Shizuku.pingBinder()) {
            showStartShizukuDialog();
            return;
        }

        // 第3步：请求 Shizuku 权限
        requestShizukuPermission();
    }

    private void requestShizukuPermission() {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        } else {
            // 已有权限，直接启动
            startFloatingService(FloatingToggleService.MODE_SHIZUKU);
        }
    }

    private void showStartShizukuDialog() {
        new AlertDialog.Builder(this, R.style.Theme_盾构bug_Dialog)
                .setTitle("启动 Shizuku")
                .setMessage("Shizuku 服务未运行。\n\n请打开 Shizuku Manager App，点击「启动」按钮，然后再返回本应用。")
                .setPositiveButton("打开 Shizuku", (dialog, which) -> {
                    Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.manager");
                    if (intent != null) {
                        startActivity(intent);
                    }
                })
                .setNeutralButton("已启动，重试", (dialog, which) -> {
                    onShizukuModeClick();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ====== 启动服务 ======

    private void startFloatingService(int mode) {
        Intent intent = new Intent(this, FloatingToggleService.class);
        intent.putExtra(FloatingToggleService.EXTRA_MODE, mode);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        // 不 finish()，而是移到后台，避免 MIUI 等国产 ROM 拦截前台服务启动
        moveTaskToBack(true);
    }
}
