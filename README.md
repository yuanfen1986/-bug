# 盾构bug — Android 网络快速切换工具

一个轻量的 Android 悬浮球工具，一键切换 WiFi 和数据网络。

---

## 功能

- **悬浮按钮**：44dp 迷你圆形悬浮球，可随意拖动位置
- **一键切换**：点击悬浮球同时切换 WiFi 和移动数据网络的开关状态
- **状态感知**：自动检测当前网络连接状态，悬浮球图标和指示灯实时反映
- **双模式**：普通模式（无需 Root）和 Shizuku 模式（提权执行）

## 两种模式

### 普通模式
使用 Android 标准 API 控制网络：

- **WiFi**：通过 `WifiManager.setWifiEnabled()`
- **移动数据**：通过反射调用 `ConnectivityManager.setMobileDataEnabled()`

> Android 10+ 上切换网络需要额外授权。移动数据反射在部分高版本系统上可能受限。

### Shizuku 模式
通过 [Shizuku](https://shizuku.rikka.app/) API 在提权进程中直接执行 Shell 命令：

- **WiFi**：`svc wifi enable/disable`
- **移动数据**：`svc data enable/disable`
- 内置备用命令链：`cmd wifi` → `settings put global`

## 截图

（待添加）

---

## 环境要求

| 项目 | 要求 |
|------|------|
| **最低 Android 版本** | 7.0 (API 24) |
| **目标 Android 版本** | 14 (API 34) |
| **Shizuku 模式** | 需要安装 Shizuku App v13+ |

## 构建

使用 Android Studio 打开项目根目录，同步 Gradle 后即可构建。

### 主要依赖

- AndroidX AppCompat 1.6.1
- Material 3 (Google Material 1.9.0)
- ConstraintLayout 2.1.4
- Shizuku API 13.1.5

### 构建命令

```bash
# Debug APK
./gradlew assembleDebug

# Release APK
./gradlew assembleRelease
```

## 使用方式

1. 安装 APK 后打开应用
2. 选择模式：
   - **普通模式**：授予悬浮窗权限即可
   - **Shizuku 模式**：需要先启动 Shizuku 并授权
3. 点击悬浮球切换网络状态：
   - **单击**：切换 WiFi / 数据网络开关
   - **长按**：退出悬浮球
   - **拖动**：移动悬浮球位置

## 技术细节

- 前台服务 + `TYPE_APPLICATION_OVERLAY` 悬浮窗（Android 8+）
- Shizuku 模式调用 `IShizukuService.newProcess()` 直接在提权进程执行 Shell 命令
- 独立开关状态跟踪（`isOffMode`），与真实网络状态解耦
- 使用 `moveTaskToBack()` 而非 `finish()`，避免国产 ROM 拦截前台服务

## License

MIT

---

# ShieldDogBug — Android Network Toggle Tool

A lightweight Android floating ball tool for toggling WiFi and mobile data with one tap.

## Features

- **Floating Button**: 44dp mini circular floating ball, freely draggable
- **One-Tap Toggle**: Tap to toggle both WiFi and mobile data on/off simultaneously
- **Status Awareness**: Auto-detects current network state with real-time icon and indicator updates
- **Dual Mode**: Normal mode (no root required) and Shizuku mode (privileged execution)

## Two Modes

### Normal Mode
Uses standard Android APIs for network control:

- **WiFi**: via `WifiManager.setWifiEnabled()`
- **Mobile Data**: via reflection on `ConnectivityManager.setMobileDataEnabled()`

> Extra permission required for network toggling on Android 10+. Mobile data reflection may be restricted on some newer systems.

### Shizuku Mode
Executes shell commands directly in a privileged process via the [Shizuku](https://shizuku.rikka.app/) API:

- **WiFi**: `svc wifi enable/disable`
- **Mobile Data**: `svc data enable/disable`
- Built-in fallback chain: `cmd wifi` → `settings put global`

## Requirements

| Item | Requirement |
|------|-------------|
| **Min Android** | 7.0 (API 24) |
| **Target Android** | 14 (API 34) |
| **Shizuku Mode** | Requires Shizuku App v13+ |

## Build

Open the project root in Android Studio, sync Gradle, and build.

### Key Dependencies

- AndroidX AppCompat 1.6.1
- Material 3 (Google Material 1.9.0)
- ConstraintLayout 2.1.4
- Shizuku API 13.1.5

### Build Commands

```bash
# Debug APK
./gradlew assembleDebug

# Release APK
./gradlew assembleRelease
```

## Usage

1. Install the APK and open the app
2. Choose a mode:
   - **Normal Mode**: Grant overlay permission
   - **Shizuku Mode**: Start and authorize Shizuku first
3. Tap the floating button to toggle network state:
   - **Tap**: Toggle WiFi / mobile data on/off
   - **Long Press**: Exit the floating button
   - **Drag**: Move the floating button

## Technical Details

- Foreground service + `TYPE_APPLICATION_OVERLAY` window (Android 8+)
- Shizuku mode uses `IShizukuService.newProcess()` for direct shell command execution in a privileged process
- Independent toggle state tracking (`isOffMode`), decoupled from actual network state
- Uses `moveTaskToBack()` instead of `finish()` to avoid foreground service being blocked by Chinese ROMs

## License

MIT
