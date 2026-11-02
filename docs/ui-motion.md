# Ready Gym UI 动效实施记录

依据 `动效规划.md`（仓库根目录）。本文件记录**实际做了什么、哪些地方与规划不同、为什么**，以及可复核的证据。凡未执行的说法都不写。

## 1. 交付一览

| 规划项 | 状态 | 落点 |
|---|---|---|
| 任务一 Motion Token | 完成 | `android/app/src/main/kotlin/com/readygym/app/Motion.kt` |
| 任务二 CoachUiState 状态机 | 完成（客户端单一状态源） | `CoachUiState.kt` + `CoachChatScreen.kt` 的 `CoachConversation` |
| 任务三 界面过渡与共享元素 | 完成 | `CoachMotion.kt`（入场/呼吸/庆祝/抖动/风险）、`FoundationCards.kt`（`sharedBounds`） |
| 任务四 性能（GIF 列表） | 完成（预加载窗口 + 纯 graphicsLayer 按压） | `ExerciseImage.kt`、`PreloadWindowTest` |
| 任务五 触觉反馈 | 完成 | `Haptics.kt` |
| 验收 1：无散落 `tween(`/`spring(` | 完成，并由测试强制 | `MotionTokenTest` |
| 验收 2：动效只由单一 `StateFlow<CoachUiState>` 驱动 | 完成，并由测试强制 | `MotionTokenTest` |
| 验收 3：Python → Android 端到端状态事件 | **未做，见 §4** | — |
| 验收 4：动画缩放 0 时功能不受阻 | 完成，并由测试覆盖 | `MotionSettings` + `CoachMotionTest` |
| 验收 5：GIF 列表掉帧 < 5% | **未实测，见 §4** | — |
| 验收 6：现有测试全绿 | 完成 | 见 §5 |

## 2. 实现要点

### 2.1 Motion.kt 是唯一动画来源

`SunnyMotion` 只暴露两档时长（200/300ms）、两条曲线、两个 spring，外加三个循环动画的 spec 工厂（打字波、骨架扫光、确认呼吸）。`MotionTokenTest` 用正则扫描 `src/main/.../com/readygym/app/**.kt`，**除 `Motion.kt` 外**出现任何 `tween(`/`spring(` 字面量、或任何非 200/300 的 `durationMillis=`，测试即失败。

减少动效通过 `MotionSettings.reduceMotion` 经 `LocalMotionSettings` 提供，在 `ReadyGymTheme` 里读一次 `Settings.Global.ANIMATOR_DURATION_SCALE`。每个动效组件在 `reduceMotion` 下**跳到终值**而不是隐藏内容：卡片、按钮、文字全部照常存在且可点。

### 2.2 状态机

`CoachUiState` 九个状态与规划一致，`wire` 字段用规划里的 snake_case。

`CoachStateReducer.reduce(...)` 是纯函数，优先级固定为：

```
risk > 进行中的工作 > 失败 > 待确认结果未知 > blocked > 草稿 > 已确认 > 空闲
```

`CoachConversation` 持有唯一一个 `MutableStateFlow<CoachUiState>`；每次状态变更（发送、确认、读回、清空、纠正、输入框编辑）末尾调用 `publishState()`。UI 侧只用 `coach.uiState.collectAsState()`，不再根据 `busy`/`error` 推断该播什么。

**风险优先级是硬约束**：`RISK_BLOCKED` 一旦成立，后续任何状态都不能把它降级；测试 `retainedRiskOutranksEveryOtherStateIncludingAnInFlightSuccess` 覆盖了全部 `CoachPending` 组合。

### 2.3 状态 → 动效映射（与规划的对应）

| 状态 | 实现 | 位置 |
|---|---|---|
| IDLE | 无循环动效；输入框可用 | — |
| THINKING / TOOL_RUNNING | 三点正弦波（相位错开，仅 `graphicsLayer`）+ 骨架卡 | `TypingDots`、`SkeletonCard` |
| PROPOSING | `AnimatedContent` + 显式 `transitionSpec`：fade + 自底部 24dp 滑入，300ms | `ProposingEnter` |
| AWAITING_CONFIRM | 确认按钮 alpha 0.7↔1.0 呼吸，全 App 唯一的循环动画 | `ConfirmBreathing` |
| SUCCESS | 单次 1→1.06→1（`springStandard`）+ 确认触觉 | `CelebrationScale` |
| FAIL / RULE_FALLBACK | 单次 4dp 横向抖动 + 拒绝触觉 | `ShakeOnce` |
| RISK_BLOCKED | 纯淡入，无弹性、无循环；一次拒绝触觉 | `RiskCardEntrance` |

`PROPOSING → AWAITING_CONFIRM` 不是两个状态播两段动画：同一张卡，确认按钮可点时才开始呼吸，输入框有未发送内容时立刻停。

### 2.4 共享元素

摘要卡 → 计划卡使用 `SharedTransitionLayout` + `sharedBounds`（Compose 1.7 库 API，本项目 `androidx.compose.animation 1.7.6`，经 BOM 2024.12.01 引入，因此**与设备 Android 版本无关**，也不需要任何低版本降级分支）。

选 `sharedBounds` 而非 `sharedElement` 的原因写在代码注释里：计划卡在两个状态里都存在，只是标签和底色变化，不是"进出场"关系；`sharedElement` 要求 `AnimatedVisibilityScope`，硬套会给列表增加一个假的可见性宿主。这里用常显的 `AnimatedVisibility` 只为满足 API 契约。

### 2.5 触觉

`Haptics` 三个语义方法：`confirm` / `reject` / `tick`，在**动画起点**（状态切换时）触发，不是动画结束时。`reduceMotion` 下动画被跳过，触觉仍然保留。

## 3. 与规划不一致的地方（重要）

### 3.1 `HapticFeedbackType.Confirm` / `DoubleTick` 在本项目不存在

规划写的是 `HapticFeedbackType.Confirm` / `HapticFeedbackType.DoubleTick`。本项目 Compose 版本为 1.7.6，`HapticFeedbackType` 只有 `LongPress` 和 `TextHandleMove`（已用 `javap` 核对 `ui-release.aar`）；`Confirm`/`DoubleTick` 是 Compose UI 1.8 才有的类型。

处理：改用**平台常量** `android.view.HapticFeedbackConstants`：

- 成功 → `CONFIRM`（API 30+），API 26–29 退到 `VIRTUAL_KEY`
- 失败/风险 → `REJECT`（API 30+），退到 `LONG_PRESS`
- 普通点击 → `CLOCK_TICK`（API 30+），退到 `KEYBOARD_TAP`

语义与规划一致，且不需要为它升级整个 Compose BOM（规划 §7 要求不动依赖）。`TextHandleMove` 未被用作点击反馈。

### 3.2 "唯一状态源"的边界

规划要求 UI 层禁止自行推断该播什么动画——已满足。但**状态的来源**仍是客户端 reducer，而不是规划 §3.2 描述的"Python agent 在流式响应中发出结构化状态事件"：当前 Python 服务端是非流式的 JSON 响应，没有状态事件通道。见 §4。

`PROPOSING` 与 `AWAITING_CONFIRM` 的区别不由服务端给，而由"确认按钮此刻是否真的可点"决定（`canConfirmNow()`），因为服务端无法知道用户输入框里有没有未发送的文字。

### 3.3 优先级顺序的补充

规划只规定"`RISK_BLOCKED` 覆盖其他一切"。实现里额外规定：**进行中的工作优先于上一次的失败**。否则点"重试"后，整个请求期间界面会一直显示失败抖动，读起来像又失败了一次。`CoachUiStateReducerTest.aRetryInFlightStopsShowingTheFailure` 固定了这条。

另：`unsupported_experience` 映射为 `FAIL` 而不是 `RISK_BLOCKED`。它不是身体风险（原始契约 `coach-api.md` 也明确"不产生急性/医学风险 latch"），借用严肃风险卡会误导。

### 3.4 `RULE_FALLBACK` 与确认按钮

规则回退草稿仍然是**草稿**，仍然需要用户确认，所以它保留独立状态（按钮文案变成"确认采用规则草稿 vN"），但确认按钮照常呼吸。规划没细说这一点。

## 4. 未完成项（如实说明）

1. **验收 3（Python → Android 端到端状态事件）未做。**
   现有 Python 服务是同步 JSON 响应（`ready_gym_foundation.py` 的 `run_agent` 返回完整 `IntakeResponse`），没有流式通道，也没有 `coach_state` 字段。要打通需要改 Python 的响应协议和 Android 的解析，属于规划 §7 里"只加状态事件外发、不改工具语义"之外的协议变更，本轮未擅自改动。`CoachUiState.fromWire()` 已按规划的事件名实现，服务端一旦开始发 `coach_state`，Android 侧可以零改动接上——但**接口没有真的接**，不能算通过。

2. **验收 5（GIF 列表掉帧 < 5%）未实测。**
   模拟器上无法得到可信的帧率数据（`dumpsys gfxinfo` 在模拟器 GPU 上的数字不代表真机），因此没有执行。已做的部分：按压反馈只用 `graphicsLayer` 缩放、列表项 `animate=false` 只解首帧、`preloadWindow` 的 ±2 窗口有单测。**滚动帧率没有证据，不能声称达标。**

3. 未新增任何第三方动画库（Rive/Lottie 均未引入），符合 §7。

## 4.1 模拟器预览（后补，含实测与一个真实缺陷）

后续在 `readygym_api35` 模拟器（Android 15 / x86_64，1080×2280）上装了 debug APK 实际跑了一遍，真实 DeepSeek 对话闭环走通（建档→追问→摘要卡→确认→保存并跳「训练」）。截图在 `screenshots/motion-*.png`。

**实测到的（不是"看代码推断"）：**

- THINKING 状态：三点波 + 「教练正在回复…」出现，输入框与发送按钮同时禁用（`motion-05-frame1.png`）。
- 确认按钮呼吸：同一像素在两帧间从 `(27,115,52)` 变到 `(76,144,96)`，即 alpha 在 0.7↔1.0 之间循环（`motion-19-breath-a/b.png`）。
- **减少动效：把 `animator_duration_scale` 置 0 后重进，同一像素逐帧完全相同 `(22,112,47)`，而卡片、文字、确认按钮仍全在、仍可点**（`motion-25-reduced.png` / `motion-26-reduced-b.png`）。这是验收 4 的真机证据，不再是单测推断。

**期间发现并修掉的真实缺陷（原本单测和宿主渲染都没发现）：**

摘要卡出现后，确认按钮被底部输入区挡住，**在模拟器上根本滚不出来**——用户看不到也点不到「确认并保存档案」。原因是滚动列末尾没有为底部输入区预留空间。第一次修法是固定加 180dp 尾距，结果把「同意并开始」挤出了小视口，`AppShellTest` 当场失败 2 项；改成**只在 PROPOSING / AWAITING_CONFIRM 状态追加尾距**后两个测试恢复，模拟器上按钮也能滚到可点位置（`motion-23-card-final.png`）。

这条说明：宿主 Robolectric 渲染和绿测试不能替代真机查看——测试的视口和滚动位置与真实设备不同，恰好绕过了这个缺陷。

**仍未做：** GIF 列表滚动帧率（验收 5）依然没有证据；IME 收起展开、TalkBack、OEM 系统栏仍未验。

## 5. 证据

```
android/app/build/test-results/testDebugUnitTest/
  TEST-com.readygym.app.CoachUiStateReducerTest.xml   13 通过
  TEST-com.readygym.app.CoachMotionTest.xml            5 通过
  TEST-com.readygym.app.MotionTokenTest.xml            4 通过
  TEST-com.readygym.app.PreloadWindowTest.xml          6 通过
```

全量：`:core:test` 80 通过 / 0 失败；`:app:testDebugUnitTest` **70 通过、1 明确跳过（API26/27 Movie 解码，Robolectric 无 native shadow）、0 失败/错误**（改动前为 42 通过 / 1 跳过，新增 28 项，原有用例未删除、未修改断言）。

构建：`gradle --offline --rerun-tasks :core:test :app:testDebugUnitTest :app:assembleDebug` → `BUILD SUCCESSFUL in 1m 55s`，产物 `app/build/outputs/apk/debug/app-debug.apk`（28.1 MB）。

测试覆盖的是**行为与不变量**（状态转移、风险优先级、减少动效下仍可操作、TOKEN 不外泄），不是"动画好不好看"。视觉手感需要真机确认，本文件不作此声明。

## 6. 复现环境说明

本机 `~/.gradle` 对沙箱不可写，Gradle 会因拿不到 `native-platform.dll` 的锁而启动失败。本轮的做法是把 `~/.gradle` 镜像到 `D:\readygym\.tmp-tools\gradle-home` 并用 `GRADLE_USER_HOME` 指过去（该目录未纳入版本控制）。这是本机限制，不是项目配置，不需要写进仓库。
