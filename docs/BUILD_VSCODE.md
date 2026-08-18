# 在 VS Code 中构建 VirtualDisplayController

不装 Android Studio，用 VS Code + 命令行即可完成构建。

---

## 一、环境准备（一次性）

### 1. JDK 17

Android Gradle Plugin 8.x 需要 **JDK 17**（JDK 21 多数情况也可用）。

| 系统 | 安装方式 |
|------|----------|
| Windows | [Adoptium Temurin 17](https://adoptium.net/) 或 `winget install EclipseAdoptium.Temurin.17.JDK` |
| macOS | `brew install openjdk@17` |
| Linux | `sudo apt install openjdk-17-jdk` |

确认：

```bash
java -version   # 应显示 17 或 21
```

### 2. Android SDK（命令行工具即可）

**不必装完整 Android Studio**，只需 SDK。

#### 方式 A：只装 Command Line Tools（推荐）

1. 打开：https://developer.android.com/studio#command-tools  
2. 下载对应系统的 **Command line tools only**  
3. 解压到某个目录，例如：

```text
# Windows
C:\Android\Sdk\cmdline-tools\latest\   ← 把解压出的 cmdline-tools 内容放这里

# macOS / Linux
~/Android/Sdk/cmdline-tools/latest/
```

4. 安装必要组件：

```bash
# 把 sdkmanager 加入 PATH，或写全路径
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

接受许可：

```bash
yes | sdkmanager --licenses
```

#### 方式 B：已装过 Android Studio

直接用 Studio 自带的 SDK，常见路径：

```text
Windows:  %LOCALAPPDATA%\Android\Sdk
macOS:    ~/Library/Android/sdk
Linux:    ~/Android/Sdk
```

### 3. 环境变量

在系统或终端里设置（永久写入 shell 配置更省事）：

```bash
# Linux / macOS 示例（~/.bashrc 或 ~/.zshrc）
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin

# Windows PowerShell 用户级
[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Users\你的用户名\AppData\Local\Android\Sdk", "User")
```

确认：

```bash
adb version
sdkmanager --list 2>/dev/null | head   # 或 echo $ANDROID_HOME
```

### 4. VS Code 扩展（建议）

在扩展市场安装：

- **Extension Pack for Java**（`vscjava.vscode-java-pack`）
- **Kotlin Language**（`fwcd.kotlin`）— 可选，用于语法高亮

打开本工程后，若提示安装推荐扩展，点安装即可。

---

## 二、配置本工程

### 1. 用 VS Code 打开文件夹

```text
File → Open Folder → 选择 VirtualDisplayController
```

### 2. 创建 `local.properties`

复制示例并改成你的 SDK 路径：

```bash
# Linux / macOS
cp local.properties.example local.properties
# 编辑 local.properties，例如：
# sdk.dir=/home/你的用户名/Android/Sdk

# Windows（注意路径里的反斜杠要写成 \\ 或用正斜杠）
# sdk.dir=C:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

### 3. 生成 Gradle Wrapper（首次需要）

工程里已有 `gradle/wrapper/gradle-wrapper.properties`，还需要 `gradlew` 脚本和 jar。任选一种方式：

**方式 A：本机已安装 Gradle**

```bash
cd VirtualDisplayController
gradle wrapper --gradle-version 8.2
```

**方式 B：没有全局 Gradle（推荐）**

从任意一个已有 `gradlew` 的 Android 工程复制这两个文件过来：

- `gradlew`
- `gradlew.bat`（Windows）
- `gradle/wrapper/gradle-wrapper.jar`

或临时下载官方 wrapper：

```bash
# Linux / macOS
curl -sL https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradlew -o gradlew
chmod +x gradlew
# 还需要 gradle-wrapper.jar，可用下面命令生成（需已装 gradle）或从别的项目复制
```

最省事：若你有 Android Studio，打开任意项目后，把该项目的 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` 复制到本工程即可。

---

## 三、构建步骤

在 VS Code 终端（`` Ctrl+` ``）里执行。

### 1）编译 Server JAR（特权后端）

```bash
# 确保 ANDROID_HOME 已设置
./scripts/build_server.sh

# 成功后产物：
# server/build/libs/vdserver.jar
```

Windows 可用 Git Bash / WSL 跑上述脚本，或手动：

```bash
# 手动等价命令（把 ANDROID_JAR 换成你的路径）
javac -source 17 -target 17 \
  -bootclasspath "%ANDROID_HOME%\platforms\android-34\android.jar" \
  -cp "%ANDROID_HOME%\platforms\android-34\android.jar" \
  -d server\build\classes \
  server\src\main\java\com\vdcontroller\server\*.java \
  server\src\main\java\com\vdcontroller\server\wrappers\*.java

jar cf server\build\libs\vdserver.jar -C server\build\classes .
```

### 2）编译主 App（APK）

```bash
./gradlew assembleDebug
```

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 3）安装到手机

手机开启 **开发者选项 → USB 调试**，用数据线连接：

```bash
adb devices          # 确认能看到设备
./gradlew installDebug
# 或
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4）推送并启动 Backend

```bash
adb push server/build/libs/vdserver.jar /data/local/tmp/
adb shell CLASSPATH=/data/local/tmp/vdserver.jar \
  app_process /system/bin com.vdcontroller.server.Server --name=vdcontroller
```

保持这个终端不要关（或加 `&` 后台运行）。然后再打开手机上的 App。

---

## 四、用 VS Code 任务（点一下就构建）

工程已带 `.vscode/tasks.json`，可直接用：

1. `Ctrl+Shift+P`（macOS：`Cmd+Shift+P`）
2. 输入 **Tasks: Run Task**
3. 选择：

| 任务 | 作用 |
|------|------|
| **Build Debug APK** | `./gradlew assembleDebug`（默认构建） |
| **Build Server JAR** | 编译 vdserver.jar |
| **Install Debug APK** | 安装到已连接设备 |
| **Build All (Server + APK)** | 先 Server 再 APK |
| **Clean** | 清理 |

也可 `Ctrl+Shift+B` 直接跑默认的「Build Debug APK」。

---

## 五、常见问题

### `SDK location not found`

检查 `local.properties` 里的 `sdk.dir=` 是否指向真实 SDK 目录，且该目录下有 `platforms/android-34`。

### `Unsupported class file major version`

JDK 版本不对。用 JDK 17：

```bash
# 临时指定
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleDebug
```

### `gradlew: Permission denied`

```bash
chmod +x gradlew
```

### 找不到 `platforms;android-34`

```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

### Shizuku / 权限相关

构建与安装不依赖 Shizuku。运行时若要用 Shizuku 拉起 Backend，再在手机上装 Shizuku 并授权即可。

---

## 六、推荐工作流（小结）

```text
1. 装 JDK 17 + Android SDK 命令行工具
2. 写好 local.properties（sdk.dir=...）
3. 准备好 gradlew（gradle wrapper）
4. ./scripts/build_server.sh          → vdserver.jar
5. ./gradlew assembleDebug            → app-debug.apk
6. adb install -r app/.../app-debug.apk
7. adb push vdserver.jar + 启动 Server
8. 手机打开 App → 创建 Virtual Display → 显示触控板
```

有问题把终端完整报错贴出来即可继续排查。
