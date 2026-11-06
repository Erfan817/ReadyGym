# 配置与环境变量清单

所有凭据只在本地或服务器上，不进仓库。完整字段示例见 [`server/.env.example`](../server/.env.example)。

## 服务端（Spring Boot）

| 变量 | 必填 | 默认值 | 用途 |
|---|---|---|---|
| `READYGYM_ADMIN_TOKEN` | 生产必填 | 空 | `/api/admin/**` 的 `X-Admin-Token`；**留空时管理接口全部 403**（fail-closed），不是开放 |
| `DEEPSEEK_API_KEY` | 代理通路必填 | 空 | 服务端 AI 代理的上游凭据 |
| `READYGYM_DEEPSEEK_BASE_URL` | 否 | `https://api.deepseek.com` | OpenAI 兼容网关 `/v1` 根地址 |
| `READYGYM_DEEPSEEK_MODEL` | 否 | `deepseek-chat` | 代理使用的模型 ID；与 App 直连的 `deepseek-flash` 可能不同 |
| `READYGYM_XFYUN_APP_ID` / `API_KEY` / `API_SECRET` | 语音功能必填 | 空 | 讯飞听写，留空则 ASR 不可用 |
| `READYGYM_GIFS_DIR` | 否 | 空 | 外置 GIF 全量镜像目录；空则用 jar 内 15 个 |
| `READYGYM_DOWNLOADS_DIR` | 否 | 空 | APK 下载目录；空则关闭 `/download/**` |
| `READYGYM_H2_PASSWORD` | 生产建议 | 空 | H2 文件库口令 |
| `MYSQL_URL` / `MYSQL_USER` / `MYSQL_PASSWORD` | 仅 mysql profile | — | 切 MySQL 时用，`useSSL=true` |

策略项在 `server/src/main/resources/application.yml` 的 `readygym.*` 下：`ai.daily-limit-per-device`（默认 60）、`ai.max-tokens`、`gifs-dir`、`downloads-dir`、`zone`（Asia/Shanghai）。

> **重要**：`spring.jpa.hibernate.ddl-auto` 生产为 `update`。改成 `validate` 会让现有 VPS 启动失败；要引入显式迁移（Flyway）必须先备份 H2 并验证已有 schema。

## Android（构建期注入）

`android/local.properties` 被两层 `.gitignore` 排除，字段：

| 字段 | 用途 |
|---|---|
| `sdk.dir` | 本机 Android SDK 路径 |
| `readyGymAiBaseUrl` / `readyGymAiModel` / `readyGymAiApiKey` | App 直连 AI 网关；**三项任一为空即回落服务端 `/api/ai` 代理** |
| `readyGymPgyerApiKey` / `readyGymPgyerAppKey` | 蒲公英检查更新；留空则关闭 |

`android/gradle.properties` 的 `readyGymServiceBaseUrl` **进 git**，是后端地址；本地调试用 `-PreadyGymServiceBaseUrl=http://10.0.2.2:8080` 覆盖。
换服务器或切 HTTPS 时，必须同步改 **`gradle.properties:13` + `app/src/main/res/xml/network_security.xml` + `app/src/debug/res/xml/debug_network_security.xml`** 三处（后两者文件名不同）。

正式签名开关：`READYGYM_KEYSTORE_FILE`、`READYGYM_KEYSTORE_PASSWORD`、`READYGYM_KEY_ALIAS`、`READYGYM_KEY_PASSWORD`，四项齐全且 keystore 存在才启用；否则保持 debug 签名。

## 已知边界

- App 直连模式下网关凭据必然随 APK 分发，必须可轮换并限制额度；根治方案是统一走服务端代理（需先把代理的 `max-tokens` 提到 ≥4000 并补齐视觉能力）。
- 当前公网入口是明文 HTTP，Bearer token、语音、反馈与 AI 计划内容均可被嗅探。上 TLS 前不要把这些当作机密传输。
