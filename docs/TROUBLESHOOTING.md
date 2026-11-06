# 故障排查手册

先分流：**绕过 App 直接 curl 复现**，判断是 App、服务端还是上游的问题。Spring 默认不打访问日志，「日志里没有」不代表请求没到。

## 构建

| 现象 | 处理 |
|---|---|
| `DaemonCrashedException` | Kotlin daemon 偶发崩溃，直接重跑一次 |
| R8 报 dex 被占用 | `./gradlew --stop` → 删除 `app/build/intermediates/dex` 里被锁文件 → 重跑 |
| 单测出现 `UncaughtExceptionsBeforeTest`（Room 跨测试泄漏） | 重跑一次；`AppShellTest` 的 `@After` 不要删 |
| Robolectric 测试真连外网 | 涉及网络客户端的测试必须用注入空地址构造（如 `VoiceInputController(activity, serviceBaseUrl = "")`） |
| `gradle.properties` 注释与 wrapper 版本不符 | 注释里「Gradle 9.4+」是滞后描述，当前 wrapper 是 8.9 |

## AI 不可用

1. 确认走的是哪条通路：构建注入 `AI_API_KEY` → App 直连网关；任一为空 → 回落服务端 `/api/ai`。**新克隆/CI/漏配 `local.properties` 都会触发回落**。
2. 直连模式：检查 `local.properties` 三项、手机网络能否访问网关域名。
3. 代理模式：检查 VPS 的 `DEEPSEEK_API_KEY` / `READYGYM_DEEPSEEK_BASE_URL` / `MODEL` 是否有效，以及 `/api/ai/chat/completions` 是否带 Bearer token。
4. 上游 401/402/403 → 服务端返回 `upstream_unavailable`（不要报成用户凭据错误）；429 → `upstream_rate_limited`；其余 5xx → `upstream_error`。
5. 计划生成失败还要核对 token 预算：计划用 4000，普通聊天 1500，视觉 ≥2000。**推理型模型的思考也耗 token**，给少了 content 会是空。

## 语音不可用

```bash
# 先绕过 App 测服务端（seconds 必须与 App 录音上限一致，约 59）
curl -s -X POST <地址>/api/asr -H 'Content-Type: application/json' \
  -d '{"audioB64":"<16k PCM base64>","seconds":59}'
```

- 400 `invalid_audio`：base64 或 PCM 长度（>2,000,000 字节）不合法。
- 413 `request_too_large`：请求体超过 3 MB。
- 429 `rate_limited`：触发 per-IP 限流，等窗口过去再试。
- `asr_busy`：流式会话占满上限，检查是否有只 `/start` 不 `/finish`/`/abort` 的客户端。
- 模拟器**没有真实麦克风与相机**，只能验证失败/降级路径；真机验收不可省略。
- 流式任一步失败 App 会自动回落整段 `/api/asr`，所以老接口绝对不能删。

## 拍照识别不可用

- `vision` 为 null（未注入网关 key 的构建）时，App 应给出明确提示而不是静默；手动选择器械仍可用。
- 视觉请求**不走 JSON 模式**，且 `max_tokens` 必须 ≥2000；请求形状是 OpenAI `image_url`。
- 先 curl 网关发图复现，分清是 App 压缩、网络还是模型。

## 检查更新异常

- 蒲公英 `buildHaveNewVersion` 字段**不可靠**，只信 `buildVersionNo > 本地 versionCode`。
- 返回 429/5xx 时 App 显示「网络不通，稍后再试」；`PGYER_API_KEY` 留空则显示「还没有配置」。
- 上传中文更新说明必须先写成 UTF-8 文件再 `-F "buildUpdateDescription=<desc.txt"`，命令行直接写中文会乱码。

## 运营面板 403

1. `curl -o /dev/null -w "%{http_code}" <地址>/admin.html` → 200 才是新 jar。
2. 用 `systemctl cat readygym` 只检查 `EnvironmentFile=` 路径与权限（600）；**不要** `systemctl show ... --property=Environment`，会打印明文 token。
3. `curl -H "X-Admin-Token: <token>" <地址>/api/admin/stats` → 403 说明 token 不对或没配；**未配置 token 时接口是关闭的，不是开放的**。
4. `bootRun` 本地调试用 `-PreadyGymAdminToken=...`，不要只靠命令行 `set` 环境变量（会被 Gradle 守护进程吞掉）。

## VPS / 部署

- `systemctl status readygym`、`journalctl -u readygym -f`、`free -h`。
- 分片上传脚本见 [OPERATIONS.md](OPERATIONS.md)；SFTP 整包直传经常断线，别用。
- 健康检查只返回 `status: ok`，**不查数据库**；H2 有问题时它仍可能 200。
- 发版前必须备份 jar 与 `/opt/readygym/data/`；出问题按 OPERATIONS 的回滚步骤执行。

## 数据与迁移

- Room v1→v2 只新增 `weight_entry` / `weight_target`，必须注册 `GymDatabase.MIGRATION_1_2`，**禁止破坏性迁移**；`app/schemas/1.json`、`2.json` 要保留。
- 覆盖安装验证要逐表哈希比对旧档案、计划版本、会话与已完成组记录。
- 服务端 `ddl-auto: update` 会自动改表；改 schema 前先备份 H2，不要贸然切成 `validate`。

## 模拟器验证的边界

`AVD readygym_api35`；中文输入不支持（`adb shell input text` 用 `%s` 转义空格）；截图坐标是缩放过的，点按要用 UI 树里的真实像素坐标。模拟器结论**不能**写成真机性能或可用性结论。
