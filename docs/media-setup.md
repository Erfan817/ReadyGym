# 可选动作素材接入

开源源码可在没有商业动作图片/GIF的情况下编译和运行。动作目录、文字说明、计划规则
保留；缺素材时演示无法播放，当前训练列表显示动作名占位，器械指南显示文字/失败提示。
287 条目录不代表附赠 287 份媒体许可。

先取得适用于自己的授权，再按下列现有映射添加素材：

1. `exercise-library.json` 的 `gifAsset` 相对 `android/app/src/main/assets/`。
2. `gifPath` 由你自己的服务器 `/gifs/<gifPath>` 分发；设置 `READYGYM_GIFS_DIR`
   指向自己的已授权素材目录，保持路径层级。服务器未内置商业图库。
3. 历史 `ExerciseMediaCatalog` 的六个 GIF/JPG 路径是可选接入位置。

这些本地媒体目录已被 .gitignore 排除。不要用别的动作、测试夹具或随机图片冒充演示，
不要把作者的 ReadyGym 服务器当作开源项目公共 CDN。
原有 CC 动画与单像素测试夹具仅用于各自明确的测试用途，详见 THIRD_PARTY_NOTICES.md。
