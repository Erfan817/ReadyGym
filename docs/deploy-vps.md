# ReadyGym 服务器部署（VPS）

> 本文是公开仓库版本：真实主机地址、NAT 端口与凭据一律不写入文档。实际值以云控制台和服务器本地配置为准。

App 通过 `SERVICE_BASE_URL` 连接这台服务器：注册/排行榜/反馈与语音中转走 `/api/**`，器械指南的演示 GIF 走 `/gifs/**`。

## 当前部署

| 项 | 值 |
|---|---|
| 主机 | Ubuntu 22.04 VPS（2C/2G，SSH 端口见云控制台「远程地址」） |
| 服务 | `readygym.service`（systemd，开机自启，崩溃自动重启） |
| 位置 | `/opt/readygym/readygym-server.jar`，数据 `/opt/readygym/data/`（H2 文件库） |
| JVM | `/usr/lib/jvm/java-21-openjdk-amd64`（项目 toolchain 21），`-Xmx512m` |
| 内部端口 | 8080（`/api/health` 健康检查；`/gifs/**` 30 天缓存） |
| 公网入口 | `http://<VPS_HOST>:<NAT_PORT>`（控制台 NAT 转发 `<NAT_PORT>` → 8080） |
| Admin TOKEN | 保存在服务端 systemd unit 的 `Environment=` 中，调用 `/api/admin/**` 时用 `X-Admin-Token` 头；**值不写入仓库** |

App 打包地址在 `android/gradle.properties` 的 `readyGymServiceBaseUrl`。本地调试用
`-PreadyGymServiceBaseUrl=http://10.0.2.2:8080` 覆盖（模拟器访问宿主机）。

## 网络（重要）

该 VPS 的 80/443 被云平台 nginx 拦截（未备案），TCP 端口需在控制台做 NAT 转发后才真正可达
（SSH 同样走一条独立的高位端口映射）。已创建一条转发：外部 `<VPS_HOST>:<NAT_PORT>` → 内部 `8080`。
App 的明文例外只给了这一个主机（`app/src/main/res/xml/network_security.xml`）；将来上域名 +
HTTPS 后删掉例外、改 `readyGymServiceBaseUrl` 为 https 即可，其他都不用动。

## 发布新版本

```bash
cd server && ./gradlew bootJar
python - <<'EOF'   # 或任何 sftp 工具
import paramiko
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('<ip>', port=<ssh端口>, username='<部署用户>', password='<见云控制台>')
s = c.open_sftp(); s.put('build/libs/readygym-server-0.1.0.jar', '/opt/readygym/readygym-server.jar')
c.exec_command('systemctl restart readygym')
EOF
curl http://<ip>:8080/api/health
```

## 全量 GIF 分发

jar 内置器械指南的 15 个演示 GIF（约 4.7MB）。要分发仓库 `gifdb/` 全部 1,323 个时：
上传 gifdb 到服务器（如 `/opt/readygym/gifdb`），在 unit 里加
`Environment=READYGYM_GIFS_DIR=/opt/readygym/gifdb` 后 `systemctl restart readygym`——
外置目录优先于 jar 内子集，无需改代码。

## App 安装包分发

`/download/<文件名>` 分发安装包（无需登录）。当前放着 `/opt/readygym/downloads/ReadyGym-0.4.0.apk`，
公网地址 `http://<VPS_HOST>:<NAT_PORT>/download/ReadyGym-0.4.0.apk`（手机浏览器可直接下载，
Content-Type 为 `application/vnd.android.package-archive`，7 天缓存）。

发新版：出包后上传新文件名（带版本号）到 downloads 目录即可，二维码需要按新 URL 重新生成；
旧文件保留或删除均可。上传大文件建议校验 md5（SFTP 偶发中断，残件要删掉重传）。

## 运维速查

```bash
systemctl status readygym          # 状态
journalctl -u readygym -f          # 日志
systemctl restart readygym         # 重启
free -h                            # 内存（-Xmx512m，2G 机器余量充足）
```

注意：**AI 聊天不走这台 VPS**。该机是纯国内出口（Cloudflare/GitHub 443 全部不通），连不上
PackyAPI（Cloudflare 国际节点）；App 端已改为手机直连 AI 网关（见下），VPS 继续负责注册、
排行榜、GIF 分发。systemd 里的 `DEEPSEEK_API_KEY` / `READYGYM_DEEPSEEK_BASE_URL` /
`READYGYM_DEEPSEEK_MODEL` 保留着——将来换一台有国际出口的机器即可切回服务器中转。

## AI 网关（App 直连）

AI 聊天与计划生成由 App 直连 OpenAI 兼容网关（当前 PackyAPI `deepseek-flash`）：
配置放在 `android/local.properties`（已被 .gitignore，key 不进仓库）：

```properties
readyGymAiBaseUrl=https://www.packyapi.ai/v1
readyGymAiModel=deepseek-flash
readyGymAiApiKey=<见本地配置>
```

打包时注入 `BuildConfig.AI_*`；三项任一为空则自动回落到服务器 AI 代理。模拟器/本地网络
需能访问该网关域名（国内手机网络一般可达 Cloudflare；如不可达需自备可达的网关地址）。

## 运营面板（网页管理后台）

浏览器打开 `http://<VPS_HOST>:<NAT_PORT>/admin.html`（本地则是 `http://localhost:8080/admin.html`），
输入 Admin TOKEN 即可查看：用户总量 / 今日新增 / 近 7 天活跃、总训练与今日训练、AI 调用量，
近 30 天每日趋势图（新增用户 / 训练次数 / AI 调用三个指标可切换），以及用户反馈列表（可按内容、
联系方式、机型筛选）。数据每 60 秒自动刷新；TOKEN 只保存在浏览器本地，不经过其他服务器。
对应接口：`/api/admin/stats`、`/api/admin/stats/daily?days=30`（1–90 天）、`/api/admin/feedback`。

本地启动：双击 `server/启动运营面板.bat`（服务器就绪后自动打开面板）。脚本里带的是**本地调试用的默认值，
部署到公网前必须换成随机 TOKEN**，不要沿用默认值，也不要把 TOKEN 写进仓库。
它用 `-PreadyGymAdminToken=...` 把 TOKEN 传进 bootRun——**不要只靠命令行 set 环境变量**：bootRun 的应用进程
继承的是 Gradle 守护进程的环境，刚 set 的变量可能被守护进程吞掉，导致 TOKEN 静默失效、面板永远 403。

面板连不上数据时的三步排查：
1. `curl -s -o /dev/null -w "%{http_code}" <地址>/admin.html` → 200 才是新 jar（404 = 还是旧版，先发版）；
2. `systemctl show readygym --property=Environment` → 必须能看到 `READYGYM_ADMIN_TOKEN=...`；
3. `curl -H "X-Admin-Token: <TOKEN>" <地址>/api/admin/stats` → 返回 JSON 即通；403 = TOKEN 不对或没配。

## 用户反馈与检查更新（0.4.2）

- `POST /api/feedback`（匿名）：App「我的 → 帮助与更新 → 意见反馈」提交，自动附 App 版本/机型/系统。
  查看反馈：优先用上面的运营面板；命令行备选
  `curl -H "X-Admin-Token: <TOKEN>" http://<VPS_HOST>:<NAT_PORT>/api/admin/feedback`（最新在前，最多 200 条）。
- 检查更新走蒲公英 `apiv2/app/check`（App 端直连蒲公英，不经本服务器）。两个 Key 放在
  `android/local.properties`：`readyGymPgyerApiKey`（蒲公英 账户设置→API 信息）、
  `readyGymPgyerAppKey`（应用管理→应用→安装设置），打包自动注入；留空则 App 里检查更新显示未配置。
  发新版：蒲公英上传新包 → App 每日静默检查一次，「我的」页出现"有新版本"，点去下载跳蒲公英链接。
- jar 更新遇 SFTP 频繁中断时，用分片脚本 `.tmp-tools/deploy_chunked.py`（2MB/块断点续传 + md5 校验）。
