# 参与贡献

欢迎提交问题、改进建议与 Pull Request。先按 [README「本地开发」](README.md#本地开发) 跑通项目。

## 环境与命令

| 目的 | 命令（仓库根目录） |
|---|---|
| Android app + core 单测 | `cd android && ./gradlew :app:testDebugUnitTest :core:test` |
| 后端单测 | `cd server && ./gradlew test` |
| 调试包 / 正式包 | `./gradlew :app:assembleDebug` / `:app:assembleRelease` |
| 可部署 jar | `cd server && ./gradlew test bootJar` |

Windows / Git Bash 下若 `~/.gradle` 不可写，加 `GRADLE_USER_HOME=/d/readygym/.tmp-tools/gradle-home`。
**完成标准：app + core + server 单测全绿**，PR 里附上实际验证结果；不要把模拟器或单测结果当作真机证明。

## 提交与分支

- 提交信息用 `feat:` / `fix:` / `docs:` / `chore:` / `test:` 前缀，一句话说明改了什么、为什么。
- 一个提交只做一件事；改 [AGENTS.md](AGENTS.md) 第 4 节核心机制时，必须同时带上对应回归测试。
- 从 `main` 拉短生命周期分支，PR 合并回 `main`；每个发版打 `v0.8.x` tag 并更新 [CHANGELOG.md](CHANGELOG.md)。
- CI 见 `.github/workflows/ci.yml`，本地跑通再推。

## PR 检查清单

- [ ] app / core / server 单测全绿，`assembleRelease` 能出包
- [ ] 改了 kotlinx-serialization 或 Room 相关代码时，确认 `proguard-rules.pro` 与 `app/schemas/` 仍覆盖
- [ ] 改了计划/训练规则时，说明对**已有用户数据**的影响（旧计划版本、已完成组记录、Room 迁移）
- [ ] 用户界面文案为中文、口语化，错误提示告诉用户「现在能做什么」
- [ ] 没有把密钥、个人训练记录、数据库、安装包或第三方商业媒体加进提交
- [ ] 发版时把该版本的 `*-notes.txt` 与 `*-validation.json` 一起提交（安装包本身不入库）

## 凭据与数据

- 密钥只在 `android/local.properties`、本地 `server/.env` 与服务器 `EnvironmentFile`；字段清单见 [server/.env.example](server/.env.example) 与 [docs/CONFIG.md](docs/CONFIG.md)。
- `READYGYM_ADMIN_TOKEN`、`READYGYM_XFYUN_*`、`DEEPSEEK_API_KEY`、`READYGYM_KEYSTORE_*` 等绝不进仓库、文档、截图或日志。
- 排查时**不要用** `systemctl show ... --property=Environment`（会打印明文 token）；提交前可用 `gitleaks detect` 自查。
- 安全问题不要在公开 issue 里粘贴凭据或用户数据。

## 许可边界

原创源码与文档正文按 [Apache-2.0](LICENSE) 提供。动作 GIF/JPG 是 © Gym visual 的独立授权素材，
**不属于** Apache-2.0，也不属于上游非媒体数据的 MIT 许可范围；克隆或 fork 不取得媒体使用权，
第三方须自行取得许可。完整边界见 [NOTICE](NOTICE)、[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
与 [BRAND_ASSETS.md](BRAND_ASSETS.md)。
