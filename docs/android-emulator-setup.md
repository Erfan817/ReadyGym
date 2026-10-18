# Android 模拟器：在电脑上直接看 App，不用每次装到手机

这套东西现在已经在 `D:\readygym` 上装好并验证通过（Android 15 / API 35 / x86_64，App 已实际跑起来），
本文件是复用的操作说明。

## 一次性准备（已在本机完成）

| 组件 | 位置 |
|---|---|
| Android SDK | `C:\Users\aire\AppData\Local\Android\Sdk` |
| 系统镜像 android-35 | `%LOCALAPPDATA%\Android\Sdk\system-images\android-35\default\x86_64` |
| platform android-35 | `%LOCALAPPDATA%\Android\Sdk\platforms\android-35` |
| build-tools 34.0.0 | `%LOCALAPPDATA%\Android\Sdk\build-tools\34.0.0` |
| AVD `readygym_api35` | `%USERPROFILE%\.android\avd\readygym_api35.avd` |
| Gradle 8.9 | `%TEMP%\gradle-8.9`（临时目录，清理后需重下） |
| JDK 17 | `%TEMP%\jdk17`（临时目录，清理后需重下） |

> **JDK 必须是 17/21，不能是 25/26。** Android Gradle Plugin 8.7.3 解析不了 JDK 25/26 的版本字符串，
> 会直接报 `What went wrong: 25.0.2` 这种没有任何有用信息的错。

## 日常调试流程

```powershell
# 1. 启动模拟器（第一次约 40 秒进系统，之后有快照会快很多）
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd readygym_api35

# 2. 编译 + 安装 + 启动，一条命令
cd D:\readygym\android
$env:JAVA_HOME = "$env:TEMP\jdk17"
& "$env:TEMP\gradle-8.9\bin\gradle.bat" :app:installDebug --no-daemon --console=plain
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am start -n com.readygym.app/.MainActivity
```

`installDebug` 比 `assembleDebug` 更适合日常：编完直接装到已经跑着的模拟器上，不用手动 adb install。

## 看界面 / 调试

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# 截图（我用来核对 UI 就是这么做的）
& $adb shell screencap -p /sdcard/s.png
& $adb pull /sdcard/s.png "D:\readygym\build\s.png"

# 实时日志，只看自己的 App
& $adb logcat --pid=$(& $adb shell pidof com.readygym.app)

# 崩溃栈
& $adb logcat *:E AndroidRuntime:E

# 只看 Compose 相关
& $adb logcat -s Compose:D

# 点屏幕（坐标从截图量，单位就是像素）
& $adb shell input tap 540 800
& $adb shell input text "hello"
& $adb shell input swipe 540 1600 540 600
```

## 更省事的方案：Android Studio 的 Live Edit

如果你想要「改代码 → 界面立刻变，不用重装」：

1. 用 Android Studio 打开 `D:\readygym\android`
2. 选 `readygym_api35` 这个 AVD，点 Run
3. 装 **Live Edit** 插件（Settings → Plugins），Compose 的 UI 改动可以热更新

这是唯一能做到真正「改完立刻看到」的方式。命令行方案每次要重新编译（增量约 20–40 秒）。

## 当前 App 状态

截图确认：首屏正常，显示「AI嵌入·尚未开始」、「服务未配置，不能连接 AI」。
因为 debug 构建的 `SERVICE_BASE_URL` 没配，所以 AI 功能不可用——这符合预期，
要用 AI 得先起 Python + Java 服务并通过 `-PreadyGymServiceBaseUrl=http://10.0.2.2:8000` 传进去。

> 模拟器里访问宿主机用 `10.0.2.2`，不是 `localhost`。

## 常见坑

| 现象 | 原因 |
|---|---|
| `Failed to find Build Tools revision 34.0.0` | 本机只有 36.0.0，AGP 8.7.3 要 34.0.0 |
| `What went wrong: 25.0.2` | `JAVA_HOME` 指向了 JDK 25/26 |
| `SDK XML version 4` 警告 | 无害，命令行工具版本比 AGP 新，仅提示 |
| 模拟器黑屏 | 加 `-gpu swiftshader_indirect` 走软件渲染 |
| 端口连不上宿主机服务 | 用 `10.0.2.2` 代替 `127.0.0.1` |
