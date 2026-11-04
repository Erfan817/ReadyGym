# ReadyGym — Agent 交接手册

> 任何 AI Agent（ZCode / Claude Code / Codex / …）接手本项目，从本文件开始。
> 凭据一律不写入本文件，位置见「配置与凭据」。改代码前先读「核心机制」，能省你一小时考古。

## 1. 项目是什么（30 秒版）

- **安卓 App**（Kotlin + Jetpack Compose）：AI 健身教练。流程 = 聊天建档 → 确认档案 → AI 排训练计划 → 跟着练 → 练后反馈动态调整。中文 UI，面向健身新手。
- **后端**（Spring Boot, Java 21）：注册、排行榜、器械演示 GIF 分发、用户反馈、语音识别中转、APK 下载。**不做 AI 中转**（AI 由 App 直连网关）。
- **分发**：个人分发，无商店。蒲公英（主）+ VPS 直链（备）。当前版本见 `android/app/build.gradle.kts` 的 `versionCode/versionName`。
- **AI 模型**：App 直连 OpenAI 兼容网关（PackyAPI，模型 `deepseek-flash`）。该模型是**推理型 + 原生视觉**（思考耗 TOKEN；能看图）。
- **动作库**：287 个动作（12 个杠铃动作标 HIGH_RISK 对新手隐藏），60 个演示 GIF 打包进 APK、其余 227 个从服务器流式加载。
- 用户是项目主人（个人项目），改动风格可以快，但**测试基线和下面列的硬约束不许破**。

## 2. 目录结构

```
D:\readygym\
├── AGENTS.md                # 本文件
├── android\                 # Android 项目（Gradle 根）
│   ├── app\                 #   主模块：UI + ViewModel + AI 客户端 + 网络层
│   │   └── src\main\assets\exercise-library.json   # 287 动作库（version 2，含 gifPath）
│   ├── core\                #   纯 Kotlin 库（无 Android 依赖）：计划规则/动作库/PlanContract
│   ├── local.properties     #   密钥（gitignore）
│   └── gradle.properties    #   readyGymServiceBaseUrl（进 git）
├── server\                  # Spring Boot（src/main/java/com/readygym/server/…）
│   └── src\main\resources\static\gifs\   # 15 个器械演示 GIF（打包进 jar）
├── gifdb\                   # ExerciseGymGifsDB 全量镜像（© Gym visual，已获授权，见 动作数据源评估.md）
├── docs\deploy-vps.md       # 服务器部署细节展开版
├── .tmp-tools\              # 部署/验证脚本（gitignore，含 VPS 密码，勿提交勿外传）
│   ├── deploy_chunked.py    #   服务器 jar 分片上传（2MB/块+md5+自动重启）
│   └── tts.wav              #   语音链路验证用的真人语音样本
├── screenshots\             # 验证截图
└── 动效规划.md 等中文文档    # 设计笔记
```

关键源文件速查（App 侧，`android/app/src/main/kotlin/com/readygym/app/`）：

| 文件 | 职责 |
|---|---|
| `ReadyGymApp.kt` | 组合根：导航、VM 工厂、教练 VM 的 key（含 profile）、onConfirmed |
| `CoachChatScreen.kt` | `CoachConversation`（聊天 VM）：send/confirm/retry 状态机 + `CoachUiState` |
| `CoachChatScreenUi.kt` | 聊天 UI：消息流、确认卡、composer（键盘/按住语音）、`coachAvatarFor` |
| `DeepSeekCoachClient.kt` | 直连聊天客户端：SYSTEM_PROMPT（协议+计划调整规则）、JSON 解析校验、迎回档案播种 |
| `DeepSeekApi.kt` | OpenAI 兼容调用：`completeRaw`（JSON 模式）、`recognizeImage`（视觉，非 JSON 模式） |
| `VoiceInputController.kt` | 语音双引擎 + 流式上传状态机（含失败回落） |
| `GymViewModel.kt` | 档案/计划/训练会话；`generatePlan()` 走 `PlanCoach` |
| `EquipmentGuide{,Screen}.kt` | 器械目录（16 台 + 9 类额外器械映射）+ 拍照识别 UI |
| `PgyerUpdate.kt` | 蒲公英检查更新 |
| `ServerApi.kt` | 后端调用（注册/排行榜/反馈/ASR 兜底） |

core 侧（`android/core/src/main/kotlin/com/readygym/core/`）：`ai/PlanContract.kt`（计划 JSON 契约+校验+itemsHint）、`ai/PlanCoach.kt`（生成/练后调整）、`plan/WorkoutPlan.kt`（`PlanLimits`）、`exercise/ExerciseLibrary.kt`（风险过滤）、`profile/UserProfile.kt`（`describe()` 用于喂 prompt）。

## 3. 构建与测试（Windows / Git Bash）

```bash
cd android
GRADLE_USER_HOME=/d/readygym/.tmp-tools/gradle-home ./gradlew :app:testDebugUnitTest :core:test   # 全量单测
GRADLE_USER_HOME=/d/readygym/.tmp-tools/gradle-home ./gradlew :app:assembleDebug                  # 调试包
GRADLE_USER_HOME=/d/readygym/.tmp-tools/gradle-home ./gradlew :app:assembleRelease                # 分发 lite 包
cd ../server && ./gradlew test bootJar                                                            # 服务器测试 + jar
```

- release 已开 minify + shrinkResources + debug 签名。改 kotlinx-serialization 相关代码后确认 `app/proguard-rules.pro` keep 规则仍覆盖。
- **完成标准：app + core + server 单测全绿。**
- 环境坑：Kotlin daemon 偶发崩溃（`DaemonCrashedException`）→ 直接重跑；R8 报 dex 被占用 → `./gradlew --stop` 后删 `app/build/intermediates/dex` 里被锁文件再跑。

## 4. 核心机制（改动前必读）

### 4.1 教练聊天（App 直连模式）
- **换动作先看演示（0.8.12）**：`ConversationPlanPanel.kt` 在每套方案的选择按钮前展示新增/替换动作的动态 GIF、名称、器械和训练日；同一动作跨天合并，多动作横向滑动。仅改组次重量或移除动作不额外展示。`ExerciseGifPreview.kt` 与肌肉动作详情共用，优先本地 GIF，其次现有服务器路径，失败可重试；不使用模型提供的媒体 URL。播放、重试和选择方案不写计划，仍须明确确认后按原事务保存。
- **即时换动作（用户于 2026-11-01 最新要求，0.8.11）**：聊天确认替代方案后，当前未结束训练的待做动作、组次和重量必须立即同步，不能只等下次训练。`SessionPlanUpdate.kt` 保留已完成实际记录；全部组已完成或已结束的会话不重新打开。新计划与当前会话同一事务保存，旧版本迟到的组记录写入不可覆盖新动作；确认前等待排队中的完成组保存。训练页分组和输入框按计划版本/动作刷新，不只看训练编号或组数。此规则替代旧版“进行中的训练始终保留原安排”的聊天调整行为。
- **休息日（0.8.11）**：首页、开始页、计划页按 `trainingDays` 与本地日期明确展示休息日；未结束的旧训练单独提示，不盖住“今天休息”，不自动清除历史。未指定星期属于自由安排。聊天 `training_days` 经严格校验、确认卡展示再保存，缺失字段保留已有安排、显式空数组清除固定星期；禁止猜测旧聊天中未保存的星期。
- 协议**只有三种 status**：`collecting` / `ready`（带完整 profile JSON）/ `blocked`（安全路线）。初始计划仍由 `PlanCoach` 按档案生成；0.8.10 起，已有计划的 `collecting` 可附 `plan_options`，由 `ConversationPlanContract` 校验为聊天调整草稿。不得发明其他 status 或未经校验直接保存。只要声明明确 `safety_route`，即使 status 错写 collecting/ready 也优先 blocked，不接受附带计划选项。
- **老用户不重新建档（0.6.1）**：`DeepSeekCoachClient` 构造时接收已存 `UserProfile`，新会话 system prompt 播种档案（`RETURNING_PROFILE_RULE`）+ 迎回问候；`coachViewModelKey` 含 profile——档案保存/修改后下一次对话自动开新会话。已确认的卡片在 UI 一律隐藏（`confirmedCandidate.takeUnless { confirmed }`）。
- **聊天里改计划（0.8.10）**：换动作、缺具体器械、调整组次重量或整周动作安排 → 教练读取当前整周计划及可用动作，给1–3套选项 → 用户选择查看整周与逐项变化 → 明确确认后 `GymViewModel.confirmChatPlan` / `GymRepository.applyConversationPlan` 事务复核完整基线、档案及安全，再保存新版本。`plan_options` 可用整周 `days` 或跨多天 `replacements`（二选一）。不改训练日数量，继承原轮换基线，进行中的训练和当天完成门禁保留；新动作不继承旧重量。旧计划不符合现有规则时必须一并修正并说明，不能绕过新手筛选；旧徒手动作错误负重在草稿清除并展示。选项首次不合规则最多给模型一次校验反馈纠错，无效内容不写进对话历史。修改档案（包括每周天数）仍走 ready 卡→保存→计划页重新生成；档案保存立即清旧草稿并取消旧请求，迟到结果不能覆盖。
- **发送状态（0.8.10）**：点发送立即追加用户气泡、显示「已发送」并清空输入框，不等模型回复；失败显示「未收到回复，点此重试」，复用原气泡，不能重复添加或覆盖新草稿。发送新指令立即作废上一组选项；保存时禁发送/重复确认。
- 性别：`statedGender`（模型每轮必带 gender 字段；漏了由 `genderStatedIn()` 从用户原话兜底，仅驱动头像）。
- **连接可靠性（0.8.9）**：`DeepSeekApi` 对短暂断连、408/429/常见 5xx 最多自动重试一次，遵守 `Retry-After`，认证/额度耗尽不重试。推理预算耗尽时可增加预算重试，但总尝试仍为两次。`DeepSeekCoachClient` 一整轮解析成功后才提交上下文，失败后重试不能积累重复用户消息或无效回复。网络测试用注入连接，禁止真实联网。

### 4.2 计划生成与安全
- **实际记录沿用与恢复回访（用户于 2026-11-02 要求，0.8.13）**：新训练按动作与 `setIndex` 带入最近一次已结束训练的已完成组实际重量、次数、秒数；未完成组不算实际记录，不能按稀疏列表位置错位匹配。只修改说明或无关动作的计划版本不重置输入；明确确认的新负重/次数/秒数仅覆盖对应字段；移除后重新加入动作使用新计划初始值。每次结束仅展示训练总结，不立即强制询问反馈。用户选择**每完成3次训练**回访，从第3次结束的次日起在首页轻提醒，可跳过，跳过或提交后重新累计3次；旧版已保存反馈的训练不重复计数。保留首页与完成页主动反馈入口；延迟回访不能声称刚练完，反馈 `reportedAtMillis` 与训练结束时间分开记录。提醒检查点用独立 SharedPreferences，不变更 Room schema，不限制训练入口。
- `PlanCoach.generate(profile)` / `.adjust(...)` → `PlanContract` 出提示词+校验回复（两次重试后仍违规抛 `PlanRejectedException`）。调用预算 `DeepSeekApi.bundled(maxTokens=4000, readTimeoutMs=120_000)`（GymViewModel.factory），**别挪用聊天客户端的 1500**。
- `PlanContract.itemsHint()`：每天动作数由 minutesPerSession 推导（≤25→3–4，≤40→4–5，≤60→6–7，≤75→7–8，>75→8）；硬上限 `PlanLimits.ITEMS_PER_DAY = 2..8`。有分桶测试。
- **组间休息功能已取消（用户于 2026-10-28 最新要求）**：训练页不再启动休息计时，不显示休息时长、延长或跳过按钮；完成/撤销组照常保存。旧计划的 `restSeconds` / JSON `rest` 仅保留数据兼容，不再驱动界面或计时，不恢复旧版 30 秒规则。历史计划原始 JSON、当前计划版本、训练日顺序和已完成组数必须保留。
- **训练按自然日推进（用户于 2026-10-28 要求）**：有已结束且至少完成一组的训练时，按 `endedAtMillis` 的本地日期判断当天已完成；首页/开始/计划统一显示「今天已经训练完了」，不再推荐或新开当天其他训练，下一天才恢复下一训练日。已有未结束训练优先继续；跨午夜、回前台和时区变化要刷新。实际入口必须调用 `GymRepository.startSessionIfAvailable()`，在事务内检查当天完成并复用已有训练，不能仅靠隐藏按钮。
- **新手安全**：HIGH_RISK 动作（杠铃蹲/推/拉类）对新手/恢复训练者双重过滤——`Exercise.allowedFor` + `PlanContract` 只喂可用动作。这是安全约束，不可为用户要求放行。

### 4.3 语音转文字（双引擎 + 流式）
- 优先系统 `SpeechRecognizer`；设备没有识别服务时走服务器中转。**0.6.0 起流式**：按住说话时每 ~0.75s 分片 POST `/api/asr/start|stream|finish|abort`（服务器持有讯飞 WS 连接边收边转，`/finish` 只等收尾，实测松手→出字 ~0.3s）；任何失败自动回落整段 `POST /api/asr`（老接口保留，老版本 App 兼容）。
- 讯飞协议是 **WebSocket**（wss://iat-api.xfyun.cn/v2/iat，HMAC 签名握手；发 HTTP POST 会 403）。`XfAsrClient.WsClient` 是手写 RFC6455 单连接客户端。三件套 Key 只在服务器 systemd（`READYGYM_XFYUN_*`）。
- `seconds` 校验上限必须与 App 录音上限（~59s）一致——曾错写成 `@Max(8)` 导致长句全 400（0.8.1 之前的事故）。
- 识别成功后 composer 自动切回输入框展示转写（回归测试 `CoachMotionTest.recognizedSpeechLandsInTheTextFieldForReview`）；失败留在语音条可重试。

### 4.4 器械指南 + 拍照识别
- 16 台器械离线教程 + 15 个演示 GIF（服务器 `/gifs/` 分发；划船机无匹配，不用相近图冒充）。
- 拍照识别：照片本机压到 ≤1280px/JPEG70 → `DeepSeekApi.recognizeImage()`（OpenAI `image_url` 格式，**不走 JSON 模式**，**max_TOKENs ≥ 2000**——推理型模型思考也耗 TOKEN，给少了个位数的预算 content 会是空）。回复先 `matchEquipment()` 匹配 16 台（名称/别名/英文，最长命中，平局→不自动选）；不在 16 台则 `matchExtraMachine()` 匹配 9 类常见器械（蝴蝶机/史密斯机/龙门架/哈克/腿弯举/髋外展内收/提踵/山羊挺身/辅助引体机，中英文关键词），给出动作库动作+GIF 面板；再不行回手动。照片只在这一步出本机。
- 隐藏 bug 教训：TakePicture 成功回调里别忘了赋值 photoUri（0.7.0 修过一次漏赋值导致照片不显示）。

### 4.5 其他
- **头像**：首页/聊天统一 `coachGender(聊天 statedGender) ?: profile.gender`，默认男。两页必须一致。
- **检查更新**：蒲公英 `buildHaveNewVersion` 字段不可靠，App 端自己比对 `远端 versionCode > 本地`（`PgyerUpdate.parseCheckReply`）。老用户 App 每天静默查一次。
- **反馈**：匿名 `POST /api/feedback`；查看 `GET /api/admin/feedback`（头 `X-Admin-Token`，在服务器 systemd unit）。
- **明文 HTTP 白名单**只有 `REDACTED_HOST`（`app/src/main/res/xml/network_security.xml` + debug 变体同名文件，换服务器两处都改）。

## 5. 配置与凭据（都不进 git）

| 文件 | 内容 |
|---|---|
| `android/local.properties` | `readyGymAiBaseUrl/AiModel/AiApiKey`（AI 网关）、`readyGymPgyerApiKey/PgyerAppKey`（蒲公英）——打包注入 BuildConfig |
| `android/gradle.properties` | `readyGymServiceBaseUrl`（进 git；本地调试用 `-PreadyGymServiceBaseUrl=http://10.0.2.2:8080`） |
| VPS systemd unit | `READYGYM_ADMIN_TOKEN`、`READYGYM_XFYUN_APP_ID/API_KEY/API_SECRET` |
| `.tmp-tools/deploy_chunked.py` | VPS root 密码（过期时向用户索取） |

| 凭据 | 找谁/在哪 |
|---|---|
| VPS root 密码 | 用户（云控制台）；曾暴露过，提醒定期更换 |
| AI 网关 key、蒲公英 key | `android/local.properties` |
| 蒲公英账号 | 用户 |

## 6. 发版流程（三步）

**默认发布规则（用户于 2026-10-25 明确要求）**：新功能完成并通过 app、core、server 测试后，主动递增版本、构建 release 包、上传蒲公英并验证可下载，再向用户汇报；不再另行询问是否发布。除非用户当次明确要求暂不发布。凭据只从现有本地配置读取，所有安装包、更新说明、脚本和中间产物保存在 `D:\readygym` 内。

1. **bump**：`android/app/build.gradle.kts` 的 versionCode +1、versionName 递增。
2. **出包**：`assembleRelease` → `app/build/outputs/apk/release/app-release.apk`，在项目内 `releases/` 保存一份 `ReadyGym-<版本>-lite.apk`。遵守全局产物保存规则，不向项目外的桌面复制或删除文件，除非用户当次另有明确指示。
3. **传蒲公英**：
   ```bash
   # 中文更新说明必须先写进 UTF-8 文件再 -F "buildUpdateDescription=<desc.txt"，
   # 直接在 Git Bash 命令行写中文会被 Windows 编码搞成乱码。
   curl -s -F "_api_key=$KEY" -F "buildInstallType=1" \
        -F "buildUpdateDescription=<desc.txt" \
        -F "file=@app/build/outputs/apk/release/app-release.apk" \
        https://www.pgyer.com/apiv2/app/upload
   ```
   返回 `code:0` 即成功。验证：`apiv2/app/check` 传 `appKey + buildVersionCode=<上一版>`，确认返回的 `buildVersionNo` 是新 code 且 `downloadURL` 非空（`buildHaveNewVersion` 不可靠，别看它）。
   可选：同包用 `deploy_chunked.py` 传 VPS `/opt/readygym/downloads/` 作备用直链。

## 7. 服务器（VPS REDACTED_HOST）

- **SSH 端口 REDACTED_SSH_PORT**（NAT，22 不通）；公网入口只有 `REDACTED_PORT → 8080`；80/443 被备案拦截，只用高位端口。
- systemd 服务 `readygym`：jar 在 `/opt/readygym/readygym-server.jar`，JDK 21，H2 数据在 `/opt/readygym/data/`。重启 `systemctl restart readygym`，日志 `journalctl -u readygym -f`。**注意：Spring 默认不打请求访问日志，"日志里没有"不代表请求没到。**
- 该机**纯国内出口**（Cloudflare/GitHub 不通）→ AI 绝不走它中转；apt 源已换阿里云镜像。
- 部署：`server` 目录 `./gradlew test bootJar` → `python /d/readygym/.tmp-tools/deploy_chunked.py`（分片+md5+自动重启+健康检查）。SFTP 直传经常断线，别用。

## 8. 验证（模拟器 / 真机 / 接口）

- **模拟器**：AVD `readygym_api35`。android-emulator 插件：`android_build_and_run`（**module 必须填 `:app`**，默认挑 `:core` 会失败）→ `android_ui_resolve` 定位 → `android_ui_tap` → `android_screenshot` 断言。截图坐标是缩放过的，**点按要用 UI 树里的真实像素坐标**。中文输入不支持，`adb shell input text` 用 `%s` 转义空格。模拟器无真实麦克风/相机（相机是虚拟场景），语音识别和器械识别只能验证"失败/降级路径"。
- **识别/语音接口级验证**：不用真机也能测——语音用 `.tmp-tools/tts.wav` 重采样 16k 后分片 POST；器械识别直接 curl 网关发图（图源 `gifdb/`）。
- **Robolectric 测试禁网**：涉及网络客户端的测试用注入空地址构造（如 `VoiceInputController(activity, serviceBaseUrl = "")`），否则单测会真连外网。
- 排查"语音/识别不可用"类问题：先绕过 App 直接 curl 复现（`/api/asr` 带 `seconds=59`；网关带图），分清是 App、服务器还是上游的锅。

## 9. 硬约束清单（违反=返工）

1. 测试全绿才算完成；`UncaughtExceptionsBeforeTest`（Room 跨测试泄漏）重跑一次即可，`AppShellTest` 的 @After 别删。
2. 凭据/密码不进 git、不进文档、不进 APK 注释。Key 只在 `local.properties` 和服务器 systemd。
3. 讯飞/蒲公英/AI 网关的行为差异（WS-only、buildHaveNewVersion 失效、控制台编码乱码、推理模型 TOKEN 预算）都已在本文件注明，别靠猜。
4. HIGH_RISK 动作对新手不可见是安全约束，不可绕过。
5. 服务器老接口不删（旧版 App 在外面跑着）：`/api/asr` 整段上传必须保留。
6. 改服务器地址要同步改两处 network_security.xml；明文白名单只有这一个 IP。
7. 用户界面文案用中文、口语化、不出现技术术语；错误提示要告诉用户"现在能做什么"。

## 10. 当前状态（2026-11-02，v0.8.13 / code 32）

v0.8.13：训练页按动作、组号沿用上次实际完成的重量、次数及秒数，修复稀疏完成组错位，跨无关计划变更仍沿用；明确修改的计划字段采用新安排。每次结束仅训练总结与勋章；每完成3次训练，从次日起首页恢复回访，可跳过且重启保持，主动反馈随时可用。回访问最近几次训练及现在的身体感觉，保存单独回访时间，模型不再误称刚练完。全量378项通过、1项原有跳过；正式包从0.8.12覆盖升级所有数据库表哈希一致，模拟器验证18.5kg×7次、44秒实际记录带入与重启、跳过持久化、完成总结分流、保存反馈不改档案及计划。已发布蒲公英code32。包 `releases/ReadyGym-0.8.13-lite.apk`，验证 `releases/ReadyGym-0.8.13-validation.json`，发布 `releases/ReadyGym-0.8.13-published.json`，截图 `screenshots/v0.8.13/`，说明 `docs/design/training-carry-recovery-v0.8.13.md`。未做真机验证。

v0.8.12：聊天选择调整方案前直接播放新换入/新增动作的 GIF，展示动作名称、器械和对应训练日；同动作跨天去重，多动作左右滑动，加载失败可重试。与肌肉详情共用 `ExerciseGifPreview.kt`。全量367项通过、1项原有跳过。正式包覆盖0.8.11后数据库全部表哈希一致；实际App收到三套方案，本地器械推胸与在线绳索夹胸GIF逐帧截图确认动态播放，左右滑动及选择后预览不写数据库，明确确认后只新增一版、同步未结束会话待做动作，旧计划及已完成实际记录保留。包 `releases/ReadyGym-0.8.12-lite.apk`，验证 `releases/ReadyGym-0.8.12-validation.json`，发布记录 `releases/ReadyGym-0.8.12-published.json`，截图 `screenshots/v0.8.12/`，说明 `docs/design/chat-plan-gif-v0.8.12.md`。未做真机验证。

v0.8.11：聊天确认换动作后，同一事务同步当前训练待做动作/组次/重量，保留已完成实际记录、旧计划和当天门禁。修复同训练编号、同组数时页面仍显示旧动作及输入框数值，以及排队中的组保存与确认新方案竞争。首页、开始、计划按星期明确显示休息日，旧未结束训练单独提示；聊天新增 `training_days` 严格解析、确认展示与持久化，兼容缺失字段保留已有星期，修复7天安排保留上限。全量363项通过、1项原有跳过；正式包从模拟器v0.4.0/schema1覆盖升级，旧档案/计划/35会话/420组逐表哈希一致。真实App聊天预览不写入，确认后当前同一训练立即显示新动作，原已完成31秒平板支撑记录不变，重启持久化；三个页面休息日展示通过。已发布蒲公英code30。包 `releases/ReadyGym-0.8.11-lite.apk`，验证 `releases/ReadyGym-0.8.11-validation.json`，发布记录 `releases/ReadyGym-0.8.11-published.json`，截图 `screenshots/v0.8.11/`，说明 `docs/design/immediate-plan-rest-days-v0.8.11.md`。旧聊天未保存的星期不猜测恢复，需在档案选择或重新告知教练并确认；未做真机验证。

近期已交付：语音流式提速（松手→出字 ~0.3s）、语音成功落回输入框、老用户免重复建档、首页/聊天头像随性别一致、器械拍照识别（16 台教程 + 9 类额外器械给动作建议）、聊天调整计划（档案确认卡→计划页重新生成）、每天动作数上限放宽到 8。

v0.8.2 新增：计划页右上角「按肌肉选动作」→ 正背面人体图点选 18 个肌群 → 按档案筛选动作、查看 GIF → 加入指定训练日。入口及详情在 `MuscleExerciseScreen.kt`，人体图在 `MuscleBodyMap.kt`，核心筛选与添加规则在 `core/exercise/MuscleExercisePicker.kt`。写入时复核档案和版本、禁止重复及每天超过 8 个动作，保存新计划版本；`WorkoutPlan.progressionBaseVersion` 保持手动修改前后的训练日轮换，进行中的训练保留原安排。

v0.8.2 已上传蒲公英并验证 code 21、中文更新说明及下载入口；正式包保存在 `releases/ReadyGym-0.8.2-lite.apk`。默认新功能完成后直接发布，见第 6 节。Windows 旧 dex 文件持续占用时，本次通过 `.tmp-tools/release-isolated.gradle` 在项目内的新目录完成正式构建。

v0.8.3 仅修改新增动作/计划缺省的组间休息，旧计划仍读出 90 秒；已由 v0.8.4 修复，不能把仅改默认值当作老用户生效。

v0.8.4：已有当前计划读取即对齐休息 30 秒，草稿及新写入计划一致；进行中的旧训练每完成一组也从 30 秒计时，仍可手动「+30 秒」。不改计划版本及训练日轮换，不丢历史和已完成组数。新增旧 90/120 秒计划与恢复会话的回归测试，224 项测试通过、1 项原有测试跳过；正式包覆盖 0.8.3 的同一旧计划后已确认计时从 30 秒启动。已上传蒲公英并验证 code 23，包在 `releases/ReadyGym-0.8.4-lite.apk`。

v0.8.5：在「我的 → 帮助与更新」的「意见反馈」下新增「软件交流群」，打开 `GroupChatScreen.kt` 显示用户提供的 QQ 群二维码（`drawable-nodpi/community_group_qr.webp`，无损裁剪原图）及群号 `158476703`，支持复制群号；离线可查看。正式包已验证入口顺序、完整图片、复制内容及返回操作；测试通过，已上传蒲公英并验证 code 24，包在 `releases/ReadyGym-0.8.5-lite.apk`。

v0.8.6：整体页面与动效调整。首页按建档/无计划/开始/继续训练切换主卡；进行中的旧训练标题和进度取自会话本身。训练页突出当前组，完成热区 48dp、支持撤销与轻触觉，示范可收起；休息区固定、不再挤动组数表，仍从 30 秒开始。档案确认后自动到计划页；保存期间不许用旧档案生成，旧草稿及晚返回请求失效。聊天资料文字不循环闪动，上翻阅读时保留位置并提示新回复。同级页面短淡入并保存各页状态，统计优先显示本周进度，历史入口打开独立记录页；练后回顾展示实际完成的组数和动作。全量测试 244 项通过、1 项原有测试跳过；正式包已在模拟器验证旧会话保留、完成/撤销、30 秒及延长/跳过、休息区位置、统计/历史返回、系统关闭动画可操作。已上传蒲公英并验证 code 25 与中文说明，包在 `releases/ReadyGym-0.8.6-lite.apk`。截图在 `screenshots/v0.8.6/`，设计记录在 `docs/design/ui-motion-v0.8.6.md`。未做真机帧率测量，不宣称性能指标达标。

v0.8.7：打赏页替换为用户新提供的微信赞赏码。原图归档 `data/source-media/donate/1000016237-original.png`，从原图 `(353,190)-(859,696)` 无损裁出完整 506×506 白色码区，保留四周白边，不重绘码点或头像；资源仍为 `drawable-nodpi/donate_qr.png`。图片按正方形完整适配卡片宽度，补系统栏安全区与真实截图识别说明。全量测试 244 项通过、1 项原有测试跳过；正式包已在模拟器验证完整码区、关闭/系统返回。包在 `releases/ReadyGym-0.8.7-lite.apk`，截图在 `screenshots/v0.8.7/donate-release.png`，发布验证结果见 `releases/ReadyGym-0.8.7-published.json`。

v0.8.8：统计页本周指标下新增体重趋势卡，按用户参考图实现绿色折线/面积、金色目标虚线、目标差额、点选日期读数；默认显示最近30次真实称重，单点不造连线，档案体重只标记填写值而不补虚构历史。支持0.1kg记录、补记过去日期、同日覆盖、历史修改/确认删除及设置/清除目标。数据仅本机保存，不修改训练档案或当前计划。Room从v1升至v2，仅新增 `weight_entry` / `weight_target`，必须注册 `GymDatabase.MIGRATION_1_2`，禁止破坏性迁移；保留 `schemas/.../1.json` 和 `2.json`。界面在 `WeightChartCard.kt` / `WeightTrackingSheets.kt`，整合在 `WeightTrackingPanel.kt` / `WeightViewModel.kt`，存储为 `data/WeightRepository.kt`，core规则为 `weight/WeightTracking.kt`。全量261项测试通过、1项原有跳过；模拟器实际v1安装升级逐表核对旧档案/5版计划/35会话/420组原样保留，正式包验证记录/补记/修改/删除、目标、点选及重启持久化；展示样例称重在验证后清除。已上传蒲公英并验证code27，包在 `releases/ReadyGym-0.8.8-lite.apk`，截图在 `screenshots/v0.8.8/`，设计说明 `docs/design/weight-tracking-v0.8.8.md`。

v0.8.9：改善 AI 连接可靠性，短暂断线/临时服务错误最多自动重试一次；推理预算耗尽时增加预算后再试，回复解析成功后才提交整轮对话，避免手动重试重复上下文。取消组间休息计时、延长、跳过及相关展示，旧休息字段仅作数据兼容。当天有已结束且至少完成一组的训练时，首页/开始/计划页统一显示「今天已经训练完了」，当天不新开训练，次日恢复下一训练日；已有未结束训练优先继续。完成归属取本地结束日期，跨午夜/回前台/时区变化刷新，实际创建在数据库事务内检查日期并复用已有训练。全量 296 项测试通过、1 项原有跳过；正式包从 0.8.8 覆盖安装后逐表哈希核对旧档案/计划/35 会话/420 组原样保留，验证完成/撤销无休息、当天三个入口与重启仍完成，以及前台真实跨午夜自动恢复下一日。已上传蒲公英并验证 code 28 和中文说明；包在 `releases/ReadyGym-0.8.9-lite.apk`，发布记录 `releases/ReadyGym-0.8.9-published.json`，截图 `screenshots/v0.8.9/`，设计说明 `docs/design/training-daily-v0.8.9.md`。本次两次网关直连均成功，未复现外部网关故障，不承诺上游永远稳定。

v0.8.10：聊天发送立即出用户气泡、显示「已发送」并清空输入，失败在原气泡重试不重复、不覆盖新草稿。已有计划的聊天直接读当前整周计划与可用动作，反馈缺器械/不想做动作/组次重量调整可给1–3套选项，查看逐项变化和完整周安排后明确确认才存新版本。`ConversationPlanContract.kt` 处理完整 days 或跨多天 replacements；`ConversationPlanPanel.kt` 展示选择/预览/确认；`GymRepository.applyConversationPlan` 事务复核完整计划与档案并重验安全，保留轮换基线/已开始训练/当天完成门禁。旧计划不合适动作必须一并修正并说明，徒手错误负重仅在新方案清除；模型第一次无效方案可自动纠正一次，失败内容不写历史。所有明确安全路线优先blocked。全量348项通过、1项原有跳过。正式包真实App验证：已发气泡+空输入+思考，收到两套替代方案，选择预览DB不变，确认后只新增v2且progressionBaseVersion=1，旧v1原文、档案/35会话/420组/反馈逐行不变；重开新计划持久化，首页/计划/开始仍当天完成。已上传蒲公英并验证code29、中文说明及实际下载包SHA256一致。包 `releases/ReadyGym-0.8.10-lite.apk`，截图 `screenshots/v0.8.10/`，设计 `docs/design/chat-plan-v0.8.10.md`，验证 `releases/ReadyGym-0.8.10-validation.json`。模型具体建议仍需用户核对辅助器械条件，prompt明确徒手不等于不需杆凳、标题与条件一致。

可能的下一步方向（未承诺，按用户需求来）：
- 额外器械逐步补全专属教程（往 `EquipmentGuideCatalog` 加，映射结构现成）。
- 讯飞额度耗尽后的成本观察（约 0.3 元/百次）。
- iOS / 鸿蒙不可用（用户已知）。
