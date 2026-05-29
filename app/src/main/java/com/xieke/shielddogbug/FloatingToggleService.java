package com.xieke.shielddogbug;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.lang.reflect.Method;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

public class FloatingToggleService extends Service {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_PACKAGE = "disconnect_package";
    public static final int MODE_NORMAL = 0;
    public static final int MODE_SHIZUKU = 1;
    public static final int MODE_DISCONNECT = 2;

    private static final String TAG = "FloatingToggleSvc";

    private int mode = MODE_NORMAL;

    private WindowManager windowManager;
    private FrameLayout floatingView;
    private View floatingIndicator;

    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private boolean isOffMode = false; // 独立开关状态

    // 断网模式相关
    private String disconnectPackage = null;
    private boolean isBlockingInternet = false;

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "floating_toggle_channel";

    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private static final int CLICK_THRESHOLD = 10;

    // ---- 悬浮球位置持久化 ---- //
    private static final String PREFS_NAME = "floating_toggle_prefs";
    private static final String PREF_POS_X = "floating_pos_x";
    private static final String PREF_POS_Y = "floating_pos_y";

    // ---- 后台线程执行 Shizuku 命令（避免阻塞 UI） ---- //
    private HandlerThread bgThread;
    private Handler bgHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 从任意线程弹出 Toast（自动切回主线程） */
    private void showToast(final String msg) {
        mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void showToast(final int resId) {
        mainHandler.post(() -> Toast.makeText(this, resId, Toast.LENGTH_SHORT).show());
    }

    // ---- Shizuku 直接执行命令 ---- //

    /**
     * 通过 Shizuku 服务器进程直接执行 shell 命令（shell/root UID 权限）
     */
    private int execShizuku(String cmd) {
        return execShizukuDirect(new String[]{"sh", "-c", cmd});
    }

    /**
     * 直接通过 Shizuku 执行命令（数组形式，不加 shell  wrapper）
     */
    private int execShizukuDirect(String[] cmd) {
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null) {
                Log.e(TAG, "Shizuku binder is null");
                return -1;
            }
            IShizukuService service = IShizukuService.Stub.asInterface(binder);
            IRemoteProcess process = service.newProcess(cmd, null, null);
            int exitCode = process.waitFor();
            StringBuilder logCmd = new StringBuilder();
            for (String s : cmd) logCmd.append(s).append(' ');
            Log.d(TAG, "exec: " + logCmd + "-> " + exitCode);
            return exitCode;
        } catch (Exception e) {
            StringBuilder logCmd = new StringBuilder();
            for (String s : cmd) logCmd.append(s).append(' ');
            Log.e(TAG, "execShizukuDirect failed: " + logCmd, e);
            return -1;
        }
    }

    private boolean isShizukuReady() {
        return Shizuku.pingBinder() && Shizuku.getBinder() != null;
    }

    // ---- WiFi 状态广播 ---- //

    private final BroadcastReceiver wifiStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())) {
                updateFloatingButtonState();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        bgThread = new HandlerThread("toggle-worker");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        registerReceiver(wifiStateReceiver, new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION));

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (floatingView != null) {
                    floatingView.post(() -> updateFloatingButtonState());
                }
            }
            @Override
            public void onLost(Network network) {
                if (floatingView != null) {
                    floatingView.post(() -> updateFloatingButtonState());
                }
            }
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (floatingView != null) {
                    floatingView.post(() -> updateFloatingButtonState());
                }
            }
        };
        connectivityManager.registerDefaultNetworkCallback(networkCallback);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.notification_text))
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setOngoing(true)
                    .build();
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_MODE)) {
            mode = intent.getIntExtra(EXTRA_MODE, MODE_NORMAL);
        }

        if (mode == MODE_DISCONNECT) {
            if (intent != null && intent.hasExtra(EXTRA_PACKAGE)) {
                disconnectPackage = intent.getStringExtra(EXTRA_PACKAGE);
            }
            // 不持久化拦截状态，每次启动默认未拦截
            isBlockingInternet = false;
        }

        if (floatingView == null) {
            createFloatingView();
        }

        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.channel_desc));
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private void createFloatingView() {
        floatingView = (FrameLayout) LayoutInflater.from(this).inflate(R.layout.floating_button, null);
        floatingIndicator = floatingView.findViewById(R.id.floating_indicator);

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        params.x = prefs.getInt(PREF_POS_X, 100);
        params.y = prefs.getInt(PREF_POS_Y, 300);

        windowManager.addView(floatingView, params);

        updateFloatingButtonState();
        setupTouchListener(params);
        setupLongPress();
    }

    private void setupTouchListener(WindowManager.LayoutParams params) {
        floatingView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    try {
                        float deltaX = event.getRawX() - initialTouchX;
                        float deltaY = event.getRawY() - initialTouchY;
                        params.x = initialX + (int) deltaX;
                        params.y = initialY + (int) deltaY;
                        windowManager.updateViewLayout(floatingView, params);
                    } catch (IllegalArgumentException ignored) {
                        return false;
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    float distance = (float) Math.sqrt(dx * dx + dy * dy);
                    float threshold = CLICK_THRESHOLD * getResources().getDisplayMetrics().density;
                    if (distance < threshold) {
                        bgHandler.post(() -> toggleNetwork());
                    } else {
                        // 拖动后保存悬浮球位置
                        saveFloatingPosition(params.x, params.y);
                    }
                    return true;
            }
            return false;
        });
    }

    private void setupLongPress() {
        floatingView.setOnLongClickListener(v -> {
            Toast.makeText(FloatingToggleService.this, R.string.floating_exit, Toast.LENGTH_SHORT).show();
            stopSelf();
            return true;
        });
    }

    // ====== 开关逻辑 ======

    private void toggleNetwork() {
        if (mode == MODE_DISCONNECT) {
            toggleDisconnectInternet();
            return;
        }

        isOffMode = !isOffMode;
        boolean targetState = !isOffMode;

        if (mode == MODE_SHIZUKU && !isShizukuReady()) {
            showToast("Shizuku 未连接，请确认已启动 Shizuku 并授权");
            return;
        }

        executeToggle(targetState);
        // 无需延迟轮询——NetworkCallback 会在网络状态实际变化时自动更新 UI
    }

    private void executeToggle(boolean targetState) {
        if (mode == MODE_SHIZUKU) {
            toggleWifiShizuku(targetState);
            toggleMobileDataShizuku(targetState);
        } else {
            toggleWifiNormal(targetState);
            toggleMobileDataNormal(targetState);
        }
    }

    // ---- 普通模式 ----

    private void toggleWifiNormal(boolean enable) {
        try {
            wifiManager.setWifiEnabled(enable);
            showToast(enable ? R.string.floating_wifi_on : R.string.floating_wifi_off);
        } catch (SecurityException e) {
            showToast(R.string.floating_wifi_fail);
        }
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    private void toggleMobileDataNormal(boolean enable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            showToast(R.string.floating_data_fail);
            return;
        }
        try {
            Method setMobileData = ConnectivityManager.class
                    .getDeclaredMethod("setMobileDataEnabled", boolean.class);
            setMobileData.setAccessible(true);
            setMobileData.invoke(connectivityManager, enable);
            showToast(enable ? R.string.floating_data_on : R.string.floating_data_off);
        } catch (Exception e) {
            showToast(R.string.floating_data_fail);
        }
    }

    // ---- Shizuku 模式（直接通过 Shizuku 服务器进程执行命令）----

    private void toggleWifiShizuku(boolean enable) {
        int exitCode = execShizuku("svc wifi " + (enable ? "enable" : "disable"));
        if (exitCode == 0) {
            showToast(enable ? R.string.floating_wifi_on : R.string.floating_wifi_off);
            return;
        }
        // 备用：cmd wifi
        exitCode = execShizuku("cmd wifi set-wifi-enabled " + (enable ? "enabled" : "disabled"));
        if (exitCode == 0) {
            showToast(enable ? R.string.floating_wifi_on : R.string.floating_wifi_off);
            return;
        }
        // 备用：settings put global
        exitCode = execShizuku("settings put global wifi_on " + (enable ? "1" : "0"));
        if (exitCode == 0) {
            showToast(enable ? R.string.floating_wifi_on : R.string.floating_wifi_off);
        } else {
            showToast("WiFi 切换失败 (exit=" + exitCode + ")");
        }
    }

    private void toggleMobileDataShizuku(boolean enable) {
        int exitCode = execShizuku("svc data " + (enable ? "enable" : "disable"));
        if (exitCode == 0) {
            showToast(enable ? R.string.floating_data_on : R.string.floating_data_off);
            return;
        }
        // 备用：settings put global
        exitCode = execShizuku("settings put global mobile_data " + (enable ? "1" : "0"));
        if (exitCode == 0) {
            showToast(enable ? R.string.floating_data_on : R.string.floating_data_off);
        } else {
            showToast("数据网络切换失败 (exit=" + exitCode + ")");
        }
    }

    // ---- 断网模式：VpnService 全流量拦截 ----
    // 原理：通过 VpnService 建立 TUN 虚拟网卡接管所有流量，读取后直接丢弃
    // 兼容所有 Android 设备，不依赖 setBlocking / iptables / appops

    private void toggleDisconnectInternet() {
        if (disconnectPackage == null || disconnectPackage.isEmpty()) {
            showToast("未设置目标应用");
            return;
        }

        isBlockingInternet = !isBlockingInternet;

        if (isBlockingInternet) {
            // === 开启拦截 ===

            // 启动 VPN — 所有流量走 TUN 后丢弃（前台服务防止 OEM 杀进程）
            Intent vpnIntent = new Intent(this, BlockVpnService.class);
            vpnIntent.setAction(BlockVpnService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(vpnIntent);
            } else {
                startService(vpnIntent);
            }

        } else {
            // === 关闭拦截 ===
            Intent vpnIntent = new Intent(this, BlockVpnService.class);
            vpnIntent.setAction(BlockVpnService.ACTION_STOP);
            startService(vpnIntent);
        }

        String label = getAppLabel(disconnectPackage);
        showToast((isBlockingInternet ? "已拦截 " : "已恢复 ") + label + " 的联网");
        mainHandler.post(() -> updateFloatingButtonState());
    }

    private String getAppLabel(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    private void saveFloatingPosition(int x, int y) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(PREF_POS_X, x)
                .putInt(PREF_POS_Y, y)
                .apply();
    }

    // ---- 状态检测 ----

    @SuppressWarnings("deprecation")
    private boolean isMobileDataEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                android.telephony.TelephonyManager tm =
                        (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                if (tm != null) {
                    return tm.isDataEnabled();
                }
            } catch (Exception ignored) {}
        }
        try {
            Method getDataEnabled = ConnectivityManager.class
                    .getDeclaredMethod("getMobileDataEnabled");
            getDataEnabled.setAccessible(true);
            return (boolean) getDataEnabled.invoke(connectivityManager);
        } catch (Exception ignored) {}
        return false;
    }

    private void updateFloatingButtonState() {
        if (mode == MODE_DISCONNECT) {
            // 断网模式：显示拦截状态
            if (floatingIndicator != null) {
                int color = isBlockingInternet
                        ? ContextCompat.getColor(this, R.color.status_disabled)
                        : ContextCompat.getColor(this, R.color.status_enabled);
                floatingIndicator.setBackgroundTintList(ColorStateList.valueOf(color));
            }
            ImageView iconView = floatingView.findViewById(R.id.floating_icon);
            if (iconView != null) {
                iconView.setImageResource(isBlockingInternet
                        ? R.drawable.ic_off
                        : R.drawable.ic_wifi);
            }
            return;
        }

        boolean wifiOn = wifiManager.isWifiEnabled();
        boolean dataOn = isMobileDataEnabled();

        boolean anyNetworkOn = wifiOn || dataOn;

        if (floatingIndicator != null) {
            int color = anyNetworkOn
                    ? ContextCompat.getColor(this, R.color.status_enabled)
                    : ContextCompat.getColor(this, R.color.status_disabled);
            floatingIndicator.setBackgroundTintList(ColorStateList.valueOf(color));
        }

        ImageView iconView = floatingView.findViewById(R.id.floating_icon);
        if (iconView != null) {
            if (wifiOn) {
                iconView.setImageResource(R.drawable.ic_wifi);
            } else if (dataOn) {
                iconView.setImageResource(R.drawable.ic_signal);
            } else {
                iconView.setImageResource(R.drawable.ic_off);
            }
        }
    }

    // ====== 生命周期 ======

    @Override
    public void onDestroy() {

        if (bgThread != null) {
            bgThread.quitSafely();
        }

        try {
            unregisterReceiver(wifiStateReceiver);
        } catch (Exception ignored) {}

        if (networkCallback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }

        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {}
        }

        // 停止 VPN（如正在拦截）
        if (isBlockingInternet) {
            Intent vpnIntent = new Intent(this, BlockVpnService.class);
            vpnIntent.setAction(BlockVpnService.ACTION_STOP);
            startService(vpnIntent);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
