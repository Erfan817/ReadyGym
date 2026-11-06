# 动作媒体清单与授权边界

> 本文件是**分类清单与操作指引**，不是逐文件授权凭证。逐文件「源 ID → 归一化文件名 → 用途 → 授权依据」的映射尚未建立，补完后才能称为完整 manifest；当前先用 `THIRD_PARTY_NOTICES.md` 中的整体授权声明覆盖。

## 授权边界（务必先读）

- 动作 GIF/JPG 保留 **© Gym visual** 署名，项目主人已声明取得 **Ready Gym 使用授权，范围不含商用**。
- 媒体**不属于** Apache-2.0（本项目源码许可），也**不属于**上游 exercises-dataset 非媒体数据/说明的 MIT 许可范围。
- 克隆或 fork 本仓库**不取得**媒体使用权，也不向下游转授权；第三方须自行向 Gym visual 取得适用许可。
- 一个 CC BY-SA 4.0 的 `Squats.gif` 有独立署名与 SHA-256，见 `THIRD_PARTY_NOTICES.md`；修改/再分发需遵守该条款。
- 品牌图、二维码、图标另有边界，见 `BRAND_ASSETS.md`。

**给下游的醒目提示**：仓库里能直接拿到这些文件，不等于可以使用它们。公开仓库的可得性与「不向下游转授权」的法律声明存在事实张力，README 已加提示，不要删除。

## 已跟踪的 GIF 分类（共 93 个）

| 位置 | 数量 | 用途 | 分发形式 |
|---|---:|---|---|
| `android/app/src/main/assets/exercises/` | 60 | 随 APK 打包，常用动作离线可看 | **随安装包再分发** |
| `android/app/src/main/assets/exercise-demos/` | 7 | 早期精选演示 | 随 APK（`.gitignore:17` 的忽略规则对已跟踪文件无效） |
| `server/src/main/resources/static/gifs/` | 15 | 器械指南教程 | 随 jar 打包 + `/gifs/**` 公开 HTTP 分发 |
| `data/source-media/current-plan/` | 7 | 设计/验证样例 | 随仓库分发 |
| `data/source-media/repo-samples/` | 3 | 素材取样 | 随仓库分发 |
| 测试夹具 | 1 | HTTP 测试用 `pixel.gif` | 仅 test classpath，适用 Apache-2.0，**不是动作演示** |

`gifdb/`（约 1,323 个 GIF、372 MB）是 Gym visual 全量镜像，已被 `.gitignore` 排除，**不进版本库**；只有配置 `READYGYM_GIFS_DIR` 时才会由服务器读取。

## 待补

1. 为 60 个打包进 APK 的 GIF 建立逐文件源 ID 映射（当前归一化文件名如 `barbell-bench-press.gif` 无法反查上游 `3119-75Bgtjy` 一类 ID）。
2. 把「随 APK 分发」「服务器 HTTP 分发」「仅仓库/测试」三类用途分开登记，分别核对授权条款是否覆盖。
3. 商业化前必须替换素材或另取商用授权（README「已知限制」已注明）。
