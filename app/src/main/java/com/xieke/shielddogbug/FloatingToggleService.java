package com.xieke.shielddogbug;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
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
    public static final int MODE_NORMAL = 0;
    public static final int MODE_SHIZUKU = 1;

    private static final String TAG = "FloatingToggleSvc";

    private int mode = MODE_NORMAL;

    private WindowManager windowManager;
    private FrameLayout floatingView;
    private View floatingIndicator;

    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;

    private boolean isOffMode = false; // 独立开关状态

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "floating_toggle_channel";

    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private static final int CLICK_THRESHOLD = 10;

    // ---- Shizuku 直接执行命令 ---- //

    /**
     * 通过 Shizuku 服务器进程直接执行 shell 命令（shell/root UID 权限）
     */
    private int execShizuku(String cmd) {
        try {
            IBinder binder = Shizuku.getBinder();
            if (binder == null) {
                Log.e(TAG, "Shizuku binder is null");
                return -1;
            }
            IShizukuService service = IShizukuService.Stub.asInterface(binder);
            IRemoteProcess process = service.newProcess(
                    new String[]{"sh", "-c", cmd},
                    null, // envp
                    null  // working dir
            );
            int exitCode = process.waitFor();
            Log.d(TAG, "exec: " + cmd + " -> " + exitCode);
            return exitCode;
        } catch (Exception e) {
            Log.e(TAG, "execShizuku failed: " + cmd, e);
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

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        registerReceiver(wifiStateReceiver, new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION));

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
        params.x = 100;
        params.y = 300;

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
                        toggleNetwork();
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
        isOffMode = !isOffMode;
        boolean targetState = !isOffMode;

        if (mode == MODE_SHIZUKU && !isShizukuReady()) {
            Toast.makeText(this, "Shizuku 未连接，请确认已启动 Shizuku 并授权", Toast.LENGTH_SHORT).show();
            return;
        }

        executeToggle(targetState);
        floatingView.postDelayed(this::updateFloatingButtonState, 500);
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
            String msg = enable ? getString(R.string.floating_wifi_on) : getString(R.string.floating_wifi_off);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.floating_wifi_fail, Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    private void toggleMobileDataNormal(boolean enable) {
        try {
            Method setMobileData = ConnectivityManager.class
                    .getDeclaredMethod("setMobileDataEnabled", boolean.class);
            setMobileData.setAccessible(true);
            setMobileData.invoke(connectivityManager, enable);
            String msg = enable ? getString(R.string.floating_data_on) : getString(R.string.floating_data_off);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.floating_data_fail, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Shizuku 模式（直接通过 Shizuku 服务器进程执行命令）----

    private void toggleWifiShizuku(boolean enable) {
        int exitCode = execShizuku("svc wifi " + (enable ? "enable" : "disable"));
        if (exitCode == 0) {
            Toast.makeText(this, enable ? R.string.floating_wifi_on : R.string.floating_wifi_off, Toast.LENGTH_SHORT).show();
            return;
        }
        // 备用：cmd wifi
        exitCode = execShizuku("cmd wifi set-wifi-enabled " + (enable ? "enabled" : "disabled"));
        if (exitCode == 0) {
            Toast.makeText(this, enable ? R.string.floating_wifi_on : R.string.floating_wifi_off, Toast.LENGTH_SHORT).show();
            return;
        }
        // 备用：settings put global
        exitCode = execShizuku("settings put global wifi_on " + (enable ? "1" : "0"));
        if (exitCode == 0) {
            Toast.makeText(this, enable ? R.string.floating_wifi_on : R.string.floating_wifi_off, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "WiFi 切换失败 (exit=" + exitCode + ")", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleMobileDataShizuku(boolean enable) {
        int exitCode = execShizuku("svc data " + (enable ? "enable" : "disable"));
        if (exitCode == 0) {
            Toast.makeText(this, enable ? R.string.floating_data_on : R.string.floating_data_off, Toast.LENGTH_SHORT).show();
            return;
        }
        // 备用：settings put global
        exitCode = execShizuku("settings put global mobile_data " + (enable ? "1" : "0"));
        if (exitCode == 0) {
            Toast.makeText(this, enable ? R.string.floating_data_on : R.string.floating_data_off, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "数据网络切换失败 (exit=" + exitCode + ")", Toast.LENGTH_SHORT).show();
        }
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
        boolean wifiOn = wifiManager.isWifiEnabled();
        boolean dataOn = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dataOn = isMobileDataEnabled();
        }

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
        super.onDestroy();

        try {
            unregisterReceiver(wifiStateReceiver);
        } catch (Exception ignored) {}

        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
