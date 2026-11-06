# 数据模型与迁移

## Android 本地（Room，当前 schema v2）

机器可读的权威定义：`android/app/schemas/com.readygym.app.data.GymDatabase/1.json` 与 `2.json`。
这两个文件必须保留；改实体时重新导出 schema 并写显式迁移，**禁止破坏性迁移**。

| 表 | 主键 | 内容 | 备注 |
|---|---|---|---|
| `profile` | `id = 1` | 训练档案 JSON、更新时间 | 单行 |
| `plan_version` | `version` | 计划原始 JSON、创建时间、原因 | **只追加，不改旧版本**；`progressionBaseVersion` 控制训练日轮换 |
| `workout_session` | `id` | 训练日序号、标题、开始/结束时间、计划版本 | `startedAt` 有索引 |
| `set_log` | `id` 自增 | `sessionId`、`exerciseId`、`setIndex`、`reps`/`seconds`/`loadKg`、`done` | 外键级联删除；`sessionId`、`exerciseId` 有索引 |
| `feedback` | `sessionId` | 练后反馈 JSON | 外键级联 |
| `weight_entry` | `epochDay` | 体重（十分之一 kg 整数） | **v2 新增** |
| `weight_target` | `id = 1` | 目标体重 | **v2 新增** |

`GymDatabase.MIGRATION_1_2` 必须注册。v1→v2 只新增两张体重表，不改动任何训练/档案/计划数据。

### 硬约束（改动前读 AGENTS.md 第 4 节）

- 换动作、确认聊天方案、按肌肉加动作都在 `db.withTransaction {}` 内复核档案、计划版本与安全边界，只追加新计划版本。
- 已完成组的实际记录不可被新计划覆盖；未结束会话的待做动作要即时同步；已结束会话不重新打开。
- 旧计划的 `restSeconds` / JSON `rest` 只作数据兼容，不再驱动界面。
- 回访检查点用独立 SharedPreferences（`recovery_check_in`），刻意不进 Room；与 Room 状态可能分裂，改动时要一起核对。

## 服务端（H2 文件库 / 可选 MySQL）

`spring.jpa.hibernate.ddl-auto: update`，没有 Flyway/Liquibase 迁移脚本。改 schema 前**必须备份** `/opt/readygym/data/`，且不要贸然把 `update` 改成 `validate`（现存库可能启动失败）。

| 实体 | 表内容 |
|---|---|
| `Device` | id、昵称、`tokenHash`（只存 SHA-256）、创建时间、最后活跃 |
| `WorkoutRecord` | 设备、客户端会话 ID、开始/结束时间、完成组数、总组数、训练量、每周目标天数；`(device_id, client_session_id)` 唯一 |
| `FeedbackRecord` | 反馈文字、联系方式、App 版本/机型/系统、提交时间 |
| `AiUsage` | `(device_id, usage_day)` 唯一，AI 调用次数；服务端代理按此做每日限额 |

### 已知问题

- `WorkoutRecord.plannedDaysPerWeek` 由客户端上报，可被伪造以刷周榜；当前已加范围约束，彻底解决需要服务端保存可信目标值。
- `AiUsage` 计数曾存在 check-then-act 竞态，现改为原子更新（见 `AiProxyController`）。
- H2 文件库无自动备份、无过期清理、无自助删除入口；见 `OPERATIONS.md`。
- `/api/health` **不查数据库**，返回 200 不代表数据可用。

## 验证口径

覆盖安装验证要逐表哈希比对：旧档案、全部计划版本、训练会话、已完成组记录、体重数据。
真机验收目前缺失，模拟器结论不能写成真机结论（见 `releases/*-validation.json` 的 `realDeviceTested` 字段）。
