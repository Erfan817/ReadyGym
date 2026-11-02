# Ready Gym · v0.8.12

面向健身新手的安卓 AI 健身教练：聊天建档 → 确认资料 → 生成训练计划 → 跟着练 → 反馈与调整。

[下载安装（蒲公英）](https://www.pgyer.com/readygym) · [Android 构建说明](android/README.md) · [项目开发手册](AGENTS.md)

## 这次更新

**聊天选择调整方案前，就能看到新动作的 GIF 演示。** 每套方案标出动作名称、器械和对应训练日；多个新动作可左右滑动查看，加载失败可重试。看演示、切换方案只是预览，点击“确认采用这套调整”后才保存。

![选择前查看新动作演示](screenshots/v0.8.12/online-demo.png)

## 当前功能

- AI 对话建档，读取已保存资料；按场地、器械和训练经验生成计划。新手与恢复训练者过滤高风险杠铃动作。
- 聊天提出 1–3 套调整方案，逐项比较并预览整周；确认后同步当前训练待做动作，保留已完成记录。
- 287 个动作，60 个 GIF 随 APK 提供，其余通过服务器加载；按 18 个肌群选动作加入计划。
- 训练按本地自然日推进，固定训练星期显示休息日；不再提供组间休息计时。
- 器械指南与拍照识别、按住说话转文字、训练记录与统计、本机体重趋势、用户反馈及检查更新。

## 技术与运行

- 正式客户端：`android/`，Kotlin + Jetpack Compose；计划规则与校验在纯 Kotlin `core` 模块，Room 保存档案、计划版本、训练组记录和体重数据。
- 当前后端：`server/`，Spring Boot + Java 21，提供注册、排行榜、GIF、反馈、语音识别中转与 APK 分发。AI 聊天、计划生成和视觉识别由 App 直连配置的 OpenAI 兼容网关。
- `agent-python/`、`business-java/` 和 `frontend/` 保留为早期实现与实验，当前安卓功能以 `android/`、`server/` 和 [AGENTS.md](AGENTS.md) 为准。
- 本地密钥放在被忽略的 `android/local.properties`，服务器凭据通过环境变量配置；部署说明见 [docs/deploy-vps.md](docs/deploy-vps.md)。仓库不包含密钥、个人数据库、构建缓存或 APK。

```bash
cd android
./gradlew :app:testDebugUnitTest :core:test
./gradlew :app:assembleRelease
cd ../server
./gradlew test bootJar
```

Android 需要 SDK 35；后端需要 JDK 21。Windows 环境与完整命令见 [AGENTS.md](AGENTS.md#3-构建与测试windows--git-bash)。

## 0.8.12 验证

版本号 **0.8.12 / code 31**，全量测试 **367 项通过、1 项原有跳过**。正式包在 API 35 模拟器覆盖安装后逐表核对旧数据一致；实际聊天方案已验证在线与本地 GIF 动态播放、横向浏览、预览不写入，以及明确确认后保存和同步当前训练。未做真机验证。

[验证记录](releases/ReadyGym-0.8.12-validation.json) · [更新说明](releases/ReadyGym-0.8.12-notes.txt) · [功能设计](docs/design/chat-plan-gif-v0.8.12.md)

媒体保留 **© Gym visual** 署名，用户已声明取得 Ready Gym 使用授权，媒体不属于 MIT；见 [动作数据源评估](动作数据源评估.md) 与 [媒体署名](android/docs/media-attribution.md)。训练建议和动作说明未经专业训练或医疗认证。

---

<details>
<summary>历史：v0.4.0 Foundation Demo（以下描述不代表当前版本）</summary>

# Ready Gym · Agent Foundation Demo v0.4.0

安卓健身 App，以教练对话为中枢。按 [`docs/agent-as-foundation.md`](docs/agent-as-foundation.md) 做出的窄范围可运行 Demo；不是完整商业产品或医疗/训练专业认证。

## 一条对话完成的闭环

1. 打开明亮的教练聊天，配置专用 Demo 访问码；明确同意后，才把训练资料发到 Ready Gym 服务端与 DeepSeek。
2. 自然语言建档、多字段提取与缺项追问 → 摘要卡 → **明确确认资料**。
3. **真实 DeepSeek `deepseek-flash` 工具调用**编排 `get_profile / check_entitlement / search_exercises / propose_plan / propose_revision / get_training_history`。Python 只编排读取和提案，不能调用保存/确认工具。
4. 在同一聊天展示一周规则草稿、工具轨迹、来源和准确 GIF；**明确确认计划后，Java 才写入 PostgreSQL 并读回验证**。
5. 直接说“周三没空，改成四天” → 展示差异和待确认 v2；v1 仍生效 → 确认后保存 v2，保留历史。
6. 身体风险优先于模型与额度，停止普通训练/确认/替换。模型失败可给明确 `rule_fallback` 草稿；不把回退当模型生成，不推算负重。

**计划是 Agent 调用经过代码约束的自编规则工具，不是模型自由处方；模板未经专业审核。** 人体快捷输入、训练组日志、RAG/pgvector、支付/会员、账号与自动进阶不在本 Demo 范围。版本历史是已保存计划，不伪造已做训练。

## 真实验收与限制

- 已真实执行官方 DeepSeek + Python API + Java17 + PostgreSQL 的建档→草稿→确认 v1→自然语言修订→确认 v2→历史→风险阻断闭环。一次已验收运行共7次模型调用、已知12,750 TOKENs；另一次早期验收已知11,153 TOKENs。只使用虚构成人样例，验收后删除样例数据并读回404。这不是用户账单，失败请求可能另计费。
- Android 为 Kotlin/Compose，沿用暖白/橙/绿 SunnyTheme；六个动作的授权原始 GIF/JPG、变式与署名保持不变。聊天资料确认不跳必填表单；离线规则入口仍为可选后备。
- Demo 服务有可信 HTTPS、私密访问码与独立会话 Bearer 双层门控；访问码只在安卓本次 Activity 内存，不放 APK。模型 Key/数据库密码/内部服务凭据只在服务端私密配置，无 Git/APK/日志明文值。
- 模型调用有单会话/全局上限、并发上限、超时与有限工具循环，无自动付费重试。提示和卡片由已验证结果整理，不执行检索文本/模型输出中的任意命令。
- **不是手机验收**：Robolectric/主机 HTTP/GIF 解码与离屏图不等于真机运行。Android26/27 GIF、IME、TalkBack、OEM 系统栏仍待手机验证。
- 会话 TOKEN/对话只在服务与 Activity 内存，旋转保留、进程结束丢失；PostgreSQL 保存明确确认的档案/计划版本，但没有账号恢复功能。服务按需启停，后台数据过期删除不作准时保证，主动删除会同时移除 Java 业务数据。

最终主机测试、APK 与限定独立复验均通过；验收记录见 [`docs/verification-v0.4.0.json`](docs/verification-v0.4.0.json)。安卓142通过、1明确legacy跳过、0失败/错误；Python61、真实PostgreSQL Java13通过。v0.3.0记录保留为历史，不标识新 APK。

## 启动与交付

- [Demo 操作/安全配置](docs/foundation-demo-runbook.md)
- [共享实现契约](docs/foundation-demo-contract.md) / [Foundation OpenAPI](docs/foundation-api.json)
- [Java业务服务](docs/foundation-business.md)
- [Android构建](android/README.md)
- [Python接口](agent-python/README.md)
- [动作媒体署名](android/docs/media-attribution.md)

固定数据源 `hasaneyldrm/exercises-dataset` 提交 `7455efae41b330c265e7cd4b78dfa848e7ce5ebd`：1,324条真实元数据，当前运行时只用准确映射的六动作。用户明确声明取得 Ready Gym 图片/GIF授权；保留 **© Gym visual**，不转为 MIT，不虚构审阅过授权书。详情见 [`data/README.md`](data/README.md)。

`frontend/` 仅保留早期 React 实验，不是正式客户端。源码交付到现有**私有** `Erfan817/ready-gym`；不提交 SDK、依赖 jar、构建缓存、APK、私密配置和个人训练数据。

</details>
