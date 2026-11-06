# 历史文档索引

本目录用来**标识**已废弃或仅作历史记录的材料。为了避免 README 与现有链接失效，原文件暂不移动，只在各自抬头加了「历史文档 / 已废弃」横幅。新接手者请以 [`README.md`](../../README.md) 与 [`AGENTS.md`](../../AGENTS.md) 为现行依据。

## 根目录早期规划（安卓立项前）

| 文件 | 状态 | 与现状的冲突 |
|---|---|---|
| `技术栈.md` | 已废弃 | 推荐 React + FastAPI + PostgreSQL + pgvector；实际是 Kotlin/Compose + Spring Boot + H2 |
| `项目规划.md` | 已废弃 | 初版 Web/PWA 产品设想，模块划分未采用 |
| `安卓产品方向.md` | 已废弃 | 只记录「从 Web 转向安卓」的决策过程 |
| `商业化方向.md` | 未实施 | 广告/会员/支付设想，当前版本都没有 |
| `动效规划.md` | 历史需求（v0.4.0 基线） | 自称「唯一需求来源」；实际实施见 `docs/ui-motion.md`，组间休息计时已在 0.8.9 取消 |
| `动作数据源评估.md` | **部分有效** | 授权结论有效，但「接入阶段」描述已过期；授权边界以 `THIRD_PARTY_NOTICES.md` 为准 |

## docs/ 下的 Foundation Demo 线（v0.3.0 / v0.4.0）

`agent-as-foundation.md`、`conversation-first-ai.md`、`plan-system.md`、`foundation-business.md`、`foundation-demo-contract.md`、`foundation-demo-runbook.md`、`review-gate-v0.3.0.md`、`review-gate-v0.4.0.md`、`verification-v0.3.0.json`、`verification-v0.4.0.json`、`foundation-api.json`、`intake-api.json`。

这些描述的是 Python Agent + Java/PG 的旧架构与 `/api/intake/**` 契约，**不适用于当前安卓 + Spring Boot 服务**。其中：
- `coach-api.md` / `plan-system.md` 里「AI 尚未接通」「真实 provider 是下一步」的结论早已不成立；
- 两份 OpenAPI 是旧 Demo 契约，当前 `/api/**` 接口清单见 [`docs/API.md`](../API.md)；
- `consent: true` / `/api/intake/sessions` 的告知同意描述不适用于当前 App，见 [`docs/PRIVACY.md`](../PRIVACY.md)。

## 仍在维护的文档

| 文件 | 用途 |
|---|---|
| `README.md` | 对外产品与工程主页 |
| `AGENTS.md` | Agent / 开发者交接手册（核心机制与硬约束） |
| `CHANGELOG.md` | 版本流水账的单一来源 |
| `CONTRIBUTING.md` | 提交约定、PR 检查清单、许可边界 |
| `docs/design/` | 每个版本的功能设计说明 |
| `docs/CONFIG.md`、`API.md`、`OPERATIONS.md`、`PRIVACY.md`、`TROUBLESHOOTING.md`、`deploy-vps.md` | 现行配置、接口、运维、隐私与排障 |
| `docs/ui-design.md`、`ui-motion.md`、`tech-retrospective.md`、`ai-plan-eval.md` | 当前线的设计基线、实施记录、技术复盘、评测集（评测集仍为 WIP） |
| `docs/index.html`、`docs/images/` | 对外作品页与宣传图 |
