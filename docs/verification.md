# 开源快照验证 · 2026-10-25

版本基线：Android 0.8.8 / versionCode 27。验证对象为本公开仓库副本，
没有复用原项目的 API 密钥、生产后端配置或商业动作媒体。

| 测试集 | 通过 | 跳过 | 失败/错误 |
|---|---:|---:|---:|
| Android app / Robolectric | 154 | 1 | 0 |
| Kotlin core | 96 | 0 | 0 |
| Spring Boot server | 11 | 0 | 0 |
| 合计 | 261 | 1 | 0 |

执行：

```sh
# android/
./gradlew :app:testDebugUnitTest :core:test :app:assembleDebug
# server/
./gradlew test bootJar
```

环境：Windows、JDK 21 + Kotlin toolchain JDK 17、Gradle 8.9、SDK 35。
使用已安装依赖和 Gradle 缓存；这不等于在所有操作系统上完成全新下载验证。
Android debug APK 和服务端 bootJar 构建成功，安装包未发布到源码仓库。
原有跳过项是 Robolectric 不支持的 legacy Movie 原生绘制测试，需要真机补验。

开源适配：

- release 网络配置仅允许 HTTPS；debug 仅放行 localhost、127.0.0.1 和模拟器宿主。
- 生成的 BuildConfig 中 AI 和蒲公英凭据均为空；debug 后端为 10.0.2.2:8080。
- 动画生命周期测试通过测试注入使用 CC BY-SA 的真实动画；商业素材缺失测试确认诚实失败。
- 服务端媒体路由测试使用 test classpath 的原创纯白夹具，不包含在生产 JAR。
- 保留原有高风险过滤、Room 迁移/历史保留、30 秒休息及体重记录回归测试。
- Gitleaks 8.30.1 初次源码扫描未发现凭据；公开历史从此干净快照开始。

本记录只证明上述主机测试和构建，不宣称进行了本次开源版真机、视觉模型、
讯飞实时语音或生产部署验收。使用者仍需配置自己的服务与媒体授权。
