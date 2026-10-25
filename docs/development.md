# 本地开发

需要 JDK 21（服务器/Gradle）和 JDK 17（Kotlin core toolchain），Android SDK 35，
Android SDK Build Tools 34.0.0（AGP 8.7 默认）。Android 最低版本 8.0 / API 26。
可由 Android Studio 安装 SDK 并生成 `android/local.properties`；密钥不要提交。
Gradle wrapper 使用 8.9。

## 服务端

在 `server/` 执行 `./gradlew test bootRun`（Windows 用 `gradlew.bat`）。
H2 默认保存在本机 `server/data/`。运行所需环境变量见 `.env.example`，需手动导出；
该文件不会被 Spring 自动加载。未配置 AI/讯飞时对应接口返回未配置错误。
管理员 token 为空时不要期待运营面板数据可访问，请自行设置随机 token。

## Android

在 `android/` 执行：

```sh
./gradlew :app:testDebugUnitTest :core:test
./gradlew :app:assembleDebug
```

默认 debug 后端为 `http://10.0.2.2:8080`，对应 Android 模拟器上的本机服务器。
真机开发可用 `adb reverse tcp:8080 tcp:8080`，并添加
`-PreadyGymServiceBaseUrl=http://127.0.0.1:8080` 构建。
release 默认无服务器地址；请配置自己的 HTTPS 后端并设置自己的正式签名。
本项目仍沿用开发阶段 debug release 签名配置，不应直接作为正式签名方案。

## AI 配置

两条路径由原代码支持：

- 在服务器配置 `DEEPSEEK_API_KEY` 和网关地址/模型，App 调用自己的服务器。
- 在 gitignored `android/local.properties` 设置 `readyGymAi*`，App 直连兼容接口。

直连路径的密钥会编译进 APK；忽略配置文件只防止提交源码，**不保护 APK 内的密钥**。
自己测试请用受限凭据；对外分发建议由受控后端持有长期密钥，不上传带生产凭据的 APK。
拍照识别需要支持 image_url 的视觉模型；普通文本模型不能替代。
网关通常需要包含 `/v1` 的完整 API 根地址，按服务商实际配置。
聊天调整仍是“修改档案→确认→重新生成计划”，不是直接写入新计划。

## 测试与媒体

运行 `server/gradlew test bootJar` 验证后端。所有单测使用模拟上游或本地数据。
媒体播放测试用有独立开放许可的真实动画，通过测试注入接口验证生命周期；
不伪造商业动作已被验证。服务器路由用测试 classpath 的原创单像素夹具。
原有 API26/27 Movie 实机验证缺口保留为明确跳过项。
本次快照的验证结果见 verification.md。

## 仓库范围

这是 v0.8.8 当前 Android + core + Spring 服务端的开源快照。
旧 React、Python agent、独立 Java Demo 不属于当前发布链，未混入此仓库。
源码迁移保留 Room v1/v2 schema、MIGRATION_1_2、风险过滤及历史 ASR 兼容接口。
