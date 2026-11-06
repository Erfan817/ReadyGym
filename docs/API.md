# 后端接口说明

> 当前后端没有生成 OpenAPI/Swagger；`docs/foundation-api.json` 与 `docs/intake-api.json` 是 v0.3/v0.4 旧 Demo 的契约，**不适用于当前服务**。本文按代码列出实际接口。

基础路径：`http(s)://<服务地址>`。错误统一返回：

```json
{"error":{"code":"invalid_request","message":"请求格式不正确"}}
```

App 的 `CoachException` 直接按这个形状解析，`detail` 会作为中文提示展示。

## 鉴权矩阵

`AuthInterceptor` 默认拦截 `/api/**`，解析 `Authorization: Bearer <token>`（服务端只存 token 的 SHA-256 哈希）。`WebConfig` 显式排除以下路径：

| 路径 | 鉴权 | 说明 |
|---|---|---|
| `/api/auth/register` | 匿名 | 注册并返回 token；本轮起有 per-IP 限流 |
| `/api/health` | 匿名 | 健康检查 |
| `/api/feedback` | 匿名 | 提交反馈；本轮起有 per-IP 限流 |
| `/api/asr/**` | 匿名 | 语音中转；本轮起有 per-IP 限流 |
| `/api/admin/**` | `X-Admin-Token` | 不用 Bearer；token 未配置时**全部 403**（fail-closed，不是开放） |
| 其余 `/api/**` | Bearer | 见下表 |

## 接口清单

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/auth/register` | 注册设备、返回 token |
| GET | `/api/me` | 当前设备信息 |
| POST | `/api/me/nickname` | 修改昵称 |
| GET | `/api/health` | 健康检查（只返回 `status: ok`，不查 DB） |
| POST | `/api/ai/chat/completions` | **服务端 AI 代理回落**：Bearer + 按设备每日限额；模型、token 预算、话题护栏由服务端决定，客户端不能覆盖 |
| POST | `/api/workouts` | 上传训练完成摘要 |
| GET | `/api/leaderboard/weekly` | 周榜 |
| POST | `/api/feedback` | 匿名提交反馈 |
| GET | `/api/admin/feedback` | 管理员读取反馈（最多 200 条） |
| GET | `/api/admin/stats` | 用户/训练/AI 调用统计 |
| GET | `/api/admin/stats/daily?days=30` | 近 N 天趋势（1–90） |
| POST | `/api/asr` | 整段语音识别（**老接口，旧版 App 在用，不得删除**） |
| POST | `/api/asr/start` `/stream` `/finish` `/abort` | 流式语音识别；会话 ID 是 UUID capability，服务端有并发上限与过期清理 |
| GET | `/gifs/**` | 器械演示 GIF（公开，30 天缓存，支持 Range） |
| GET | `/download/<文件名>` | APK 下载（公开，7 天缓存；文件名仅允许 `[A-Za-z0-9._-]+\.apk`） |
| GET | `/admin.html` | 运营面板静态页（公开，接口本身有 token） |

## 请求体与限流（2026-11-05 起）

- `RequestBodyLimitFilter`：`/api/asr**` 上限 **3 MB**，其余 `/api/**` 上限 **256 KB**；超限返回 413 `request_too_large`。无 `Content-Length` 的 chunked 请求在读取时按字节计数拦截。
- 匿名写接口（注册、反馈、ASR）有 per-IP 固定窗口限流，超限返回 429 `rate_limited`。阈值见 `RateLimitFilter`，调整时务必留足正常用户余量。
- Tomcat 层另有 `max-http-form-post-size` / `max-swallow-size` 3 MB 作第二道防线。

## 兼容性硬约束

1. `/api/asr` 整段上传接口不能删——旧版 App 还在外面跑。
2. 错误体形状不能改——客户端按 `error.code` / `error.message` 解析。
3. `seconds` 校验上限必须与 App 录音上限（约 59 秒）一致，历史上错写成 `@Max(8)` 导致长句全部 400。
4. 更换服务地址要同步改 3 处（见 [CONFIG.md](CONFIG.md)）。
