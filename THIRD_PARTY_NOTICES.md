# 第三方许可与素材边界

## Apache-2.0 的范围

根目录 LICENSE 适用于 ReadyGym 原创源码及原创文档正文。第三方依赖、上游数据、
品牌图片与下列独立授权素材不因放在本仓库中而改为 Apache-2.0。

## 动作元数据

历史六动作映射及部分说明来源：
https://github.com/hasaneyldrm/exercises-dataset
固定版本：7455efae41b330c265e7cd4b78dfa848e7ce5ebd。
保留其完整 LICENSE（包括媒体例外）与 NOTICE：`third-party/exercises-dataset/`。
上游非媒体数据/说明适用 MIT；原版权为 Copyright (c) 2026 Hasan Emir Yıldırım。

当前 `android/app/src/main/assets/exercise-library.json` 为本地整理的 287 条动作目录，
包含中文提示、风险规则与媒体相对路径映射。路径曾对照 ExerciseGymGifsDB v1.1.0：
https://github.com/JahelCuadrado/ExerciseGymGifsDB/tree/v1.1.0 。
本仓库不分发该图库、原始完整元数据或其下载镜像（本地 `gifdb/` 镜像不纳入版本控制），
也不据此授予媒体许可。

## Gym visual 图片和 GIF（独立授权，不在开源许可范围内）

© Gym visual — https://gymvisual.com/
本仓库为构建可分发的 Android 安装包，随源码打包了部分动作演示 GIF/JPG，
以及服务端器械指南用图。它们仅依据项目主人单独取得的 Ready Gym 产品使用授权
用于本项目，**授权范围不含商用**，且不属于 MIT 或 Apache-2.0 授权范围。
克隆、fork 或再分发本仓库不取得这些媒体的使用权，也不构成对下游的转授权；
第三方须自行向 Gym visual 取得适用于自己用途的许可。
来源、映射与署名见 [动作数据源评估](动作数据源评估.md)、
[媒体署名](android/docs/media-attribution.md) 与 [数据说明](data/README.md)。
全量图库镜像不纳入版本控制，不由本仓库分发。

## 保留的 CC BY-SA 4.0 动画

文件：`android/app/src/main/assets/exercise-demos/squats-wensceslao.gif`
作品：《Squats.gif》；作者：Wensceslao。
来源：https://commons.wikimedia.org/wiki/File:Squats.gif
原文件：https://upload.wikimedia.org/wikipedia/commons/e/e6/Squats.gif
许可证：https://creativecommons.org/licenses/by-sa/4.0/
原始字节未修改；SHA-256：a5d98e1abccc82eaf946bacf65af914c9def49f5e5de8d6e9018ab9f1d38cdaf。
此动画保留原许可及署名；修改、再分发需遵守相应条款，无作者背书。
它用于真实动画解码与暂停/恢复测试，绝不用作其他动作的替代演示。

`server/src/test/resources/static/gifs/test-fixture/pixel.gif` 是项目生成的纯白单像素
HTTP 测试夹具，适用 Apache-2.0，仅在测试 classpath，不是动作演示。

## 构建工具和依赖

Gradle wrapper 来源于 Gradle，适用 Apache-2.0，https://github.com/gradle/gradle 。
Kotlin、AndroidX/Compose/Room、Coil、Spring Boot、Jackson 等依赖由 Gradle 解析；
各组件保留各自许可，具体版本见构建脚本及 Gradle dependency report。
重新分发二进制时仍需履行随附依赖的适用许可义务。
品牌图片见 BRAND_ASSETS.md。
