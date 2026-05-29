package com.xieke.shielddogbug;

import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.VpnService;

import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private CardView cardNormal, cardShizuku, cardDisconnect;

    // 应用列表相关
    private List<AppInfo> allApps = new ArrayList<>();
    private List<AppInfo> filteredApps = new ArrayList<>();
    private AppListAdapter appListAdapter;

    // 断网选项对话框引用（用于在选择包名后刷新显示）
    private Dialog disconnectDialog;
    private TextView tvDisconnectPackage;

    private static final int SHIZUKU_REQUEST_CODE = 1001;
    private static final String PREFS_DISCONNECT = "disconnect_prefs";
    private static final String PREF_PACKAGE = "disconnect_package";

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

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startDisconnectServices();
                } else {
                    Toast.makeText(this, "需要授予 VPN 权限才能使用断网模式", Toast.LENGTH_LONG).show();
                    sDisconnectSwitchOn = false;
                }
            });

    private boolean pendingDisconnectStart = false;

    // 断网开关状态（仅内存，不持久化，进程重启自动重置）
    private static boolean sDisconnectSwitchOn = false;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_REQUEST_CODE) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        if (pendingDisconnectStart) {
                            pendingDisconnectStart = false;
                            performDisconnectStart();
                        } else {
                            startFloatingService(FloatingToggleService.MODE_SHIZUKU);
                        }
                    } else {
                        pendingDisconnectStart = false;
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
        cardDisconnect = findViewById(R.id.card_disconnect);

        cardNormal.setOnClickListener(v -> onNormalModeClick());
        cardShizuku.setOnClickListener(v -> onShizukuModeClick());
        cardDisconnect.setOnClickListener(v -> onDisconnectModeClick());

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            overlayPermissionLauncherShizuku.launch(intent);
            return;
        }

        if (!Shizuku.pingBinder()) {
            showStartShizukuDialog();
            return;
        }

        requestShizukuPermission();
    }

    private void requestShizukuPermission() {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        } else {
            startFloatingService(FloatingToggleService.MODE_SHIZUKU);
        }
    }

    private void showStartShizukuDialog() {
        new android.app.AlertDialog.Builder(this, R.style.Theme_盾构bug_Dialog)
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

    // ====== 特定断网模式 ======

    private void onDisconnectModeClick() {
        disconnectDialog = new Dialog(this, R.style.Theme_盾构bug_Dialog);
        disconnectDialog.setContentView(R.layout.disconnect_options_dialog);
        setupFullscreen(disconnectDialog);
        disconnectDialog.getWindow().setWindowAnimations(R.style.AnimSlideRight);

        // 包名显示
        tvDisconnectPackage = disconnectDialog.findViewById(R.id.tv_selected_package);
        refreshPackageDisplay();

        // 开关（仅内存状态，进程重启默认关闭）
        SwitchCompat switchDisconnect = disconnectDialog.findViewById(R.id.switch_disconnect);
        switchDisconnect.setChecked(sDisconnectSwitchOn);
        switchDisconnect.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sDisconnectSwitchOn = isChecked;
            if (isChecked) {
                performDisconnectStart();
            } else {
                stopService(new Intent(this, FloatingToggleService.class));
                Toast.makeText(this, "已关闭断网模式", Toast.LENGTH_SHORT).show();
            }
        });

        // 返回
        disconnectDialog.findViewById(R.id.btn_back_options).setOnClickListener(v -> disconnectDialog.dismiss());

        // 点击文字卡面切换开关（只需切换开关，OnCheckedChangeListener 会自动处理后续）
        disconnectDialog.findViewById(R.id.row_enable_disconnect).setOnClickListener(v -> {
            switchDisconnect.setChecked(!switchDisconnect.isChecked());
        });

        // 选择包名
        disconnectDialog.findViewById(R.id.row_select_package).setOnClickListener(v -> {
            showAppListDialog();
        });

        disconnectDialog.show();
    }

    private void refreshPackageDisplay() {
        if (tvDisconnectPackage == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_DISCONNECT, MODE_PRIVATE);
        String pkg = prefs.getString(PREF_PACKAGE, null);
        if (pkg != null) {
            tvDisconnectPackage.setText(getString(R.string.disconnect_selected) + ": " + pkg);
        } else {
            tvDisconnectPackage.setText(R.string.disconnect_none);
        }
    }

    // ====== 应用列表全屏对话框 ======

    private void showAppListDialog() {
        Dialog dialog = new Dialog(this, R.style.Theme_盾构bug_Dialog);
        dialog.setContentView(R.layout.app_list_dialog);
        setupFullscreen(dialog);
        dialog.getWindow().setWindowAnimations(R.style.AnimSlideRight);

        // 初始化视图
        ImageButton btnBack = dialog.findViewById(R.id.btn_app_list_back);
        EditText etSearch = dialog.findViewById(R.id.et_search);
        TextView tabUserApps = dialog.findViewById(R.id.tab_user_apps);
        TextView tabSystemApps = dialog.findViewById(R.id.tab_system_apps);
        RecyclerView rvAppList = dialog.findViewById(R.id.rv_app_list);
        ProgressBar loadingView = dialog.findViewById(R.id.loading_apps);

        SharedPreferences prefs = getSharedPreferences(PREFS_DISCONNECT, MODE_PRIVATE);

        btnBack.setOnClickListener(v -> dialog.dismiss());

        // 统一设置列表界面的逻辑（加载完成后执行）
        final boolean[] isShowingSystem = {false};
        Runnable setupList = () -> {
            loadingView.setVisibility(View.GONE);
            rvAppList.setVisibility(View.VISIBLE);

            filterApps(false, "");
            setupTabStyles(tabUserApps, tabSystemApps, false);

            rvAppList.setLayoutManager(new LinearLayoutManager(this));
            appListAdapter = new AppListAdapter(filteredApps, pkgName -> {
                prefs.edit().putString(PREF_PACKAGE, pkgName).apply();
                dialog.dismiss();
            });
            rvAppList.setAdapter(appListAdapter);

            // 搜索
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    String query = s.toString().toLowerCase();
                    filterApps(isShowingSystem[0], query);
                    appListAdapter.updateData(filteredApps);
                }
            });

            dialog.setOnDismissListener(d -> refreshPackageDisplay());

            // 标签切换
            tabUserApps.setOnClickListener(v -> {
                isShowingSystem[0] = false;
                setupTabStyles(tabUserApps, tabSystemApps, false);
                filterApps(false, etSearch.getText().toString().toLowerCase());
                appListAdapter.updateData(filteredApps);
            });

            tabSystemApps.setOnClickListener(v -> {
                isShowingSystem[0] = true;
                setupTabStyles(tabUserApps, tabSystemApps, true);
                filterApps(true, etSearch.getText().toString().toLowerCase());
                appListAdapter.updateData(filteredApps);
            });
        };

        if (allApps.isEmpty()) {
            // 首次打开：后台加载，显示转圈
            loadingView.setVisibility(View.VISIBLE);
            rvAppList.setVisibility(View.GONE);
            new Thread(() -> {
                loadInstalledApps();
                runOnUiThread(setupList);
            }).start();
        } else {
            // 已加载过，直接显示
            setupList.run();
        }

        dialog.show();
    }

    private void setupFullscreen(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        window.setBackgroundDrawableResource(android.R.color.transparent);
        // 延伸绘制到状态栏下方
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
    }

    // ====== 加载已安装应用 ======

    private void loadInstalledApps() {
        allApps.clear();
        PackageManager pm = getPackageManager();
        List<PackageInfo> packages;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0));
        } else {
            packages = pm.getInstalledPackages(0);
        }

        for (PackageInfo pkgInfo : packages) {
            ApplicationInfo appInfo = pkgInfo.applicationInfo;
            if (appInfo == null) continue;

            String packageName = pkgInfo.packageName;
            CharSequence label = pm.getApplicationLabel(appInfo);
            String appName = label != null ? label.toString() : packageName;
            Drawable icon = pm.getApplicationIcon(appInfo);
            boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

            allApps.add(new AppInfo(appName, packageName, icon, isSystem));
        }

        Collections.sort(allApps, (a, b) -> a.appName.compareToIgnoreCase(b.appName));
    }

    private void filterApps(boolean systemOnly, String query) {
        filteredApps.clear();
        for (AppInfo app : allApps) {
            if (app.isSystem != systemOnly) continue;

            if (!query.isEmpty()) {
                String lowerQuery = query.toLowerCase();
                boolean matchName = app.appName.toLowerCase().contains(lowerQuery);
                boolean matchPkg = app.packageName.toLowerCase().contains(lowerQuery);
                if (!matchName && !matchPkg) continue;
            }

            filteredApps.add(app);
        }
    }

    private void setupTabStyles(TextView tabUser, TextView tabSystem, boolean systemActive) {
        int orange = getColor(R.color.accent_primary);
        int cardBg = getColor(R.color.card_bg);
        int textSec = getColor(R.color.text_secondary);

        if (systemActive) {
            tabUser.setTextColor(textSec);
            tabUser.setBackgroundTintList(android.content.res.ColorStateList.valueOf(cardBg));
            tabSystem.setTextColor(cardBg);
            tabSystem.setBackgroundTintList(android.content.res.ColorStateList.valueOf(orange));
        } else {
            tabUser.setTextColor(cardBg);
            tabUser.setBackgroundTintList(android.content.res.ColorStateList.valueOf(orange));
            tabSystem.setTextColor(textSec);
            tabSystem.setBackgroundTintList(android.content.res.ColorStateList.valueOf(cardBg));
        }
    }

    // ====== 断网模式权限检查 & 启动 ======

    private void performDisconnectStart() {
        SharedPreferences prefs = getSharedPreferences(PREFS_DISCONNECT, MODE_PRIVATE);
        String pkg = prefs.getString(PREF_PACKAGE, null);
        if (pkg == null) return;

        // 检查 VPN 权限（VpnService 需要用户授权）
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent);
        } else {
            startDisconnectServices();
        }
    }

    private void startDisconnectServices() {
        SharedPreferences prefs = getSharedPreferences(PREFS_DISCONNECT, MODE_PRIVATE);
        String pkg = prefs.getString(PREF_PACKAGE, null);
        if (pkg == null) return;

        // 启动悬浮按钮（实际的断网拦截由 FloatingToggleService 通过 Shizuku + iptables 完成）
        Intent intent = new Intent(this, FloatingToggleService.class);
        intent.putExtra(FloatingToggleService.EXTRA_MODE, FloatingToggleService.MODE_DISCONNECT);
        intent.putExtra(FloatingToggleService.EXTRA_PACKAGE, pkg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "已启动断网模式", Toast.LENGTH_SHORT).show();
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
        moveTaskToBack(true);
    }

    // ====== 数据类 ======

    private static class AppInfo {
        final String appName;
        final String packageName;
        final Drawable icon;
        final boolean isSystem;

        AppInfo(String appName, String packageName, Drawable icon, boolean isSystem) {
            this.appName = appName;
            this.packageName = packageName;
            this.icon = icon;
            this.isSystem = isSystem;
        }
    }

    // ====== RecyclerView 适配器 ======

    private static class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

        private List<AppInfo> apps;
        private final OnAppSelectedListener listener;

        interface OnAppSelectedListener {
            void onSelected(String packageName);
        }

        AppListAdapter(List<AppInfo> apps, OnAppSelectedListener listener) {
            this.apps = apps;
            this.listener = listener;
        }

        void updateData(List<AppInfo> newApps) {
            this.apps = newApps;
            notifyDataSetChanged();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            AppInfo app = apps.get(position);
            holder.icon.setImageDrawable(app.icon);
            holder.appName.setText(app.appName);
            holder.packageName.setText(app.packageName);
            holder.itemView.setOnClickListener(v -> listener.onSelected(app.packageName));
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView appName;
            final TextView packageName;

            ViewHolder(View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.iv_app_icon);
                appName = itemView.findViewById(R.id.tv_app_name);
                packageName = itemView.findViewById(R.id.tv_package_name);
            }
        }
    }
}
