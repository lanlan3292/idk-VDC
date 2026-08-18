# VirtualDisplayController 架构说明

## 目标

在本机创建一个 Virtual Display，并在 App 内显示 / 用悬浮 Touchpad 控制它；第三方 App 可在该 Virtual Display 上正常运行。

## 权限模型

| 能力 | 所需权限 | 实现方式 |
|------|----------|----------|
| 创建 VirtualDisplay | 无特殊（普通 App 也可），但部分机型校验 UID/package | shell 身份更稳妥 |
| injectInputEvent 到其他 Display | `INJECT_EVENTS` | **仅 shell / system 持有** |
| 启动 Activity 到指定 display | shell 或 `ACTIVITY_EMBEDDING` 等 | `am start --display` |

因此 **输入注入 + VirtualDisplay 管理** 必须跑在独立 Backend 进程，以 shell UID 运行。

## 启动 Backend 的两种方式

### 1. ADB shell + app_process（开发 / 调试）

```bash
adb push vdserver.jar /data/local/tmp/
adb shell CLASSPATH=/data/local/tmp/vdserver.jar \
  app_process /system/bin com.vdcontroller.server.Server --name=vdcontroller
```

### 2. Shizuku UserService / newProcess（日常使用）

主 App 通过 Shizuku 以 shell 身份执行同样的 `app_process` 命令。

## 进程通信

LocalSocket（abstract namespace `vdcontroller`），二进制协议见 `Protocol.java`。

## 手势映射

| Touchpad 手势 | VirtualDisplay 输入 |
|---------------|---------------------|
| 单指移动 | 虚拟光标移动（相对） |
| 单击抬起 | DOWN + UP |
| 长按 | DOWN 保持 |
| 按住移动 | DOWN + MOVE（拖动） |
| 双指同时移动 | ACTION_SCROLL |

Virtual Display 区域外的触摸不受影响（Touchpad 是独立 Overlay Window）。

## 画面显示

当前版本：Backend 用 ImageReader 作为 VirtualDisplay 的 Surface（保证 VD 可创建）。
主 App 预览区可后续扩展为：

1. 直接把主 App 的 Surface 传给 Backend（跨进程 Surface 共享），或
2. 编码帧后通过 socket 传输（类似 scrcpy）。

## 极简 Launcher

主 App 列出已安装可启动应用，调用 Backend `LAUNCH_APP`，Backend 执行：

```
am start --display <id> -a android.intent.action.MAIN -c android.intent.category.LAUNCHER <pkg>
```

## 参考

- [scrcpy](https://github.com/Genymobile/scrcpy) — Virtual Display、InputManager 反射注入、app_process
- [Shizuku](https://github.com/RikkaApps/Shizuku)
- [Ynkcc/VirtualDisplay](https://github.com/Ynkcc/VirtualDisplay)
