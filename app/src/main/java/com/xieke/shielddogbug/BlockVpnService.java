package com.xieke.shielddogbug;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VpnService：建立 VPN 接管所有流量，通过 TUN 口读取后直接丢弃
 *
 * 原理：
 * 1. 建立 VPN，addRoute("0.0.0.0", 0) 接管所有流量
 * 2. TUN 读取循环不断读取并丢弃所有数据包
 * 3. 不依赖 setBlocking / iptables / appops，兼容所有 Android 设备
 */
public class BlockVpnService extends VpnService {

    private static final String TAG = "BlockVpn";
    private static final int MTU = 1500;
    private static final int DNS_SERVER = 0x08080808; // 8.8.8.8
    private static final int NOTIFY_ID = 1001;

    // ====== 外部 Intent Action ======
    public static final String ACTION_START = "shielddogbug.vpn.START";
    public static final String ACTION_STOP  = "shielddogbug.vpn.STOP";

    // ====== VPN 状态 ======
    private ParcelFileDescriptor tunFd;
    private volatile boolean started = false;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ====== 生命周期 ======

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        final String action = intent.getAction();
        Log.d(TAG, "onStartCommand action=" + action);

        if (ACTION_STOP.equals(action)) {
            stopVpn();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            Log.d(TAG, "Starting VPN, blocking all traffic");
            startVpn();
        }
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        Log.w(TAG, "VPN revoked by system!");
        stopVpn();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy called, started=" + started);
        stopVpn();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ====== VPN 建立/关闭 ======

    private void startVpn() {
        if (started) {
            Log.d(TAG, "VPN already started");
            return;
        }

        try {
            Builder builder = new Builder();
            builder.setSession(getString(R.string.app_name) + " 断网");

            // TUN 虚拟网卡配置 — 接管所有流量
            builder.addAddress("10.0.0.1", 32);
            builder.addRoute("0.0.0.0", 0);
            builder.addDnsServer(InetAddress.getByAddress(new byte[]{
                    (byte) (DNS_SERVER >>> 24),
                    (byte) (DNS_SERVER >>> 16),
                    (byte) (DNS_SERVER >>> 8),
                    (byte) DNS_SERVER
            }));

            ParcelFileDescriptor fd = builder.establish();
            if (fd == null) {
                Log.e(TAG, "VPN establish returned null");
                return;
            }

            tunFd = fd;

            // 调试：检查 fd 状态
            Log.d(TAG, "fd valid=" + tunFd.getFileDescriptor().valid()
                    + " fd=" + tunFd.getFd());

            started = true;
            running.set(true);

            // 启动前台通知，防止 VIVO 等 OEM 杀掉服务
            startForeground(NOTIFY_ID, buildNotification());

            Thread reader = new Thread(this::tunReadLoop, "vpn-reader");
            reader.start();

            Log.d(TAG, "VPN established, blocking all traffic");
        } catch (Exception e) {
            Log.e(TAG, "VPN start failed", e);
            cleanup();
        }
    }

    private void stopVpn() {
        Log.d(TAG, "stopVpn called from " + Thread.currentThread().getName()
                + ", started=" + started);
        running.set(false);
        if (started) {
            started = false;
            cleanup();
        }
    }

    private void cleanup() {
        try { if (tunFd != null) { tunFd.close(); tunFd = null; } } catch (Exception ignored) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        try { stopSelf(); } catch (Exception ignored) {}
    }

    // ====== TUN 读取循环（只读不转发，直接丢弃所有流量）======

    private void tunReadLoop() {
        Log.d(TAG, "tunReadLoop started, SDK=" + Build.VERSION.SDK_INT);

        if (tunFd == null || !tunFd.getFileDescriptor().valid()) {
            Log.e(TAG, "tunReadLoop: fd is null or invalid at start");
            return;
        }

        FileDescriptor fd = tunFd.getFileDescriptor();
        byte[] buf = new byte[MTU];

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tunReadLoopWithOs(fd, buf);
        } else {
            tunReadLoopWithStream(fd, buf);
        }

        Log.d(TAG, "tunReadLoop exited, stopping VPN");
        stopVpn();
    }

    private void tunReadLoopWithOs(FileDescriptor fd, byte[] buf) {
        while (running.get()) {
            try {
                int len = android.system.Os.read(fd, buf, 0, buf.length);
                if (len < 0) {
                    Log.d(TAG, "TUN read returned " + len + " (EOF), exiting");
                    break;
                }
                // 直接丢弃 — 不做任何转发
            } catch (android.system.ErrnoException e) {
                if (e.errno == android.system.OsConstants.EAGAIN) {
                    // 非阻塞模式下无数据可用，sleep 后重试
                    try { Thread.sleep(10); } catch (InterruptedException ie) { break; }
                    continue;
                }
                Log.e(TAG, "TUN Os.read error (errno=" + e.errno + ")", e);
                break;
            } catch (Exception e) {
                if (running.get()) {
                    Log.e(TAG, "TUN read error, exiting", e);
                } else {
                    Log.d(TAG, "TUN read interrupted (running=false)");
                }
                break;
            }
        }
    }

    private void tunReadLoopWithStream(FileDescriptor fd, byte[] buf) {
        try (java.io.FileInputStream is = new java.io.FileInputStream(fd)) {
            while (running.get()) {
                try {
                    int len = is.read(buf);
                    if (len <= 0) {
                        Log.d(TAG, "TUN stream read returned " + len + ", exiting");
                        break;
                    }
                    // 直接丢弃
                } catch (java.io.IOException e) {
                    if (running.get()) {
                        Log.e(TAG, "TUN stream read error, exiting", e);
                    } else {
                        Log.d(TAG, "TUN stream read interrupted (running=false)");
                    }
                    break;
                }
            }
        } catch (java.io.IOException e) {
            Log.e(TAG, "TUN stream open error", e);
        }
    }

    // ====== 前台通知 ======

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    "vpn_block", "VPN 断网",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("VPN 断网模式运行中");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, "vpn_block");
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle(getString(R.string.app_name))
                .setContentText("断网模式运行中")
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setOngoing(true)
                .build();
    }

}
