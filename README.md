# VirtualDisplayController

在本机创建 Virtual Display，并在 App 内显示 / 用悬浮可调 Touchpad 控制。第三方 App 可在 Virtual Display 上独立运行。

## 功能

- 创建 / 销毁自定义分辨率与 DPI 的 Virtual Display
- 悬浮可拖动、可缩放的 Touchpad 区域
- 单指：移动光标 / 点击 / 长按 / 拖动
- 双指：滚动
- Touchpad 外的触摸仍是手机正常触摸
- 极简 Launcher：列出已安装 App 并启动到 Virtual Display
- Backend 独立进程（shell 权限），支持 ADB 或 Shizuku 启动

## 环境要求

- Android 8.0+（API 26+），推荐 Android 10+
- **Shizuku**（推荐）或 ADB 调试
- 悬浮窗权限（用于 Touchpad）

## 快速开始

### 1. 编译 Server JAR

```bash
# 需要 Android SDK
export ANDROID_HOME=/path/to/sdk
./scripts/build_server.sh

# 产物：server/build/libs/vdserver.jar
```

或在 Android Studio 中打开工程，先编译 `:server` 模块。

### 2. 推送并启动 Backend

**方式 A：ADB**

```bash
adb push server/build/libs/vdserver.jar /data/local/tmp/
adb shell sh /data/local/tmp/start_server.sh
# 或手动：
adb shell CLASSPATH=/data/local/tmp/vdserver.jar \
  app_process /system/bin com.vdcontroller.server.Server --name=vdcontroller
```

**方式 B：Shizuku**

1. 安装并启动 [Shizuku](https://shizuku.rikka.app/)
2. 将 `vdserver.jar` 放到 App 的 `assets/` 或 `/data/local/tmp/`
3. 打开本 App，授予 Shizuku 权限，App 会尝试自动拉起 Server

### 3. 安装并运行主 App

```bash
./gradlew :app:installDebug
# 或在 Android Studio 中 Run
```

1. 打开 App → 确认状态栏显示「已连接到 Backend」
2. 设置宽高 DPI → 点「创建 Virtual Display」
3. 点「启动应用」选择要在虚拟屏运行的 App
4. 点「显示触控板」→ 用 Touchpad 控制虚拟屏

## 工程结构

```
VirtualDisplayController/
├── app/                 # 主 UI：预览、触控板、Launcher
├── server/              # 特权 Backend（VirtualDisplay + 输入注入）
├── scripts/             # 构建 / 启动脚本
└── docs/                # 架构说明
```

## 手势说明

| 操作 | 效果 |
|------|------|
| 单指在 Touchpad 滑动 | 移动虚拟光标 |
| 单击 | 点击 |
| 长按 | 长按 |
| 按住后滑动 | 拖动 |
| 双指滑动 | 滚动 |

## 已知限制

- 部分厂商 ROM 对 VirtualDisplay 有额外限制（需 PUBLIC + OWN_CONTENT_ONLY，且尽量用 shell 身份）
- 当前预览区未实现实时画面回传（VD 内容在独立 Display 上）；完整画面镜像可参考 scrcpy 的编码传输
- Android 版本差异导致 `createVirtualDisplay` 反射签名不同，已做多路径兼容

## 参考实现

- [scrcpy](https://github.com/Genymobile/scrcpy) Virtual Display & input injection
- [Shizuku](https://github.com/RikkaApps/Shizuku)
- [Ynkcc/VirtualDisplay](https://github.com/Ynkcc/VirtualDisplay)
