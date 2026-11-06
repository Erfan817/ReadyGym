# 运维手册：备份 / 回滚 / 轮换 / 上线

> 凭据一律不写进本文件。字段清单见 [CONFIG.md](CONFIG.md) 与 [`server/.env.example`](../server/.env.example)。

## 上线前检查清单

- [ ] app / core / server 单测全绿（CI 见 `.github/workflows/ci.yml`）
- [ ] `assembleRelease` 能出包；`signingReport` 确认签名配置符合预期
- [ ] 已备份线上 jar 与 H2 数据（见下）
- [ ] `versionCode` 已递增、`CHANGELOG.md` 已更新、打了 `v0.8.x` tag
- [ ] 更新说明写成 UTF-8 文件再传给蒲公英（Git Bash 直接写中文会乱码）
- [ ] 发布后用 `apiv2/app/check` 比对 `buildVersionNo`，并核对下载包 SHA256

## 备份（每次发版前）

```bash
sudo mkdir -p /opt/readygym/backup
sudo cp /opt/readygym/readygym-server.jar /opt/readygym/backup/readygym-server-$(date +%Y%m%d-%H%M).jar
sudo cp -r /opt/readygym/data /opt/readygym/backup/data-$(date +%Y%m%d-%H%M)
```

建议每天定时备份 H2 并**异地留存**（`SCRIPT TO '...'` 导出 SQL 或停服后复制文件），每季度做一次恢复演练：把备份数据放到临时目录启动服务，确认能读到用户/训练/反馈数据。

## 回滚

jar 是同名覆盖，**没有备份就无法回退**：

```bash
sudo cp /opt/readygym/backup/<时间戳>.jar /opt/readygym/readygym-server.jar
sudo systemctl restart readygym
curl -s http://<VPS_HOST>:<NAT_PORT>/api/health
```

App 侧回滚：蒲公英下架有问题版本 / 把下载页指回旧包；`versionCode` **不要回退**（Android 拒绝降级安装），应递增 code 重新出包。

## 凭据轮换

| 凭据 | 轮换步骤 |
|---|---|
| Admin token | `openssl rand -hex 32` → 写 `/etc/readygym.env`（`chmod 600`）→ `systemctl restart readygym` → 面板里换 token。unit 里只留 `EnvironmentFile=`，不要写 `Environment=` 明文 |
| VPS 部署口令 | 云控制台改 root 口令 → 改用 SSH 公钥 + 专用低权限部署账号 → 部署脚本从 `READYGYM_VPS_*` 环境变量读取，脚本里不留明文 |
| AI 网关 key | 网关控制台轮换 → 同步 `android/local.properties` 与服务端 `DEEPSEEK_API_KEY` → 重新出包（旧包里的 key 视为已泄露） |
| 蒲公英 API key | 控制台轮换 → 同步 `local.properties` → 重新出包 |

**git 历史**：曾有 4 个提交把 IP/端口/`root`/默认 admin token/`sk-` 占位提交进库，后续脱敏提交只删了工作树的行，旧 blob 仍可用 `git show <提交>:<文件>` 取回。要彻底清除需用 `git filter-repo`（或 BFG）重写历史 + 强推 + 让所有克隆者重新 clone。**这是破坏性操作**：执行前先 `git bundle create backup.bundle --all` 并单独取得授权，本仓库当前未执行。

排查时**不要**用 `systemctl show readygym --property=Environment`——它会把 token 明文打到终端。

## 启用 TLS（P0 待办，需要域名与证书）

1. 申请域名并解析到 VPS；用 nginx/Caddy 终止 TLS 后反代到 `127.0.0.1:8080`（80/443 被备案拦截时可用非标端口 + 反代）。
2. 或直接在 Spring 侧启用 `server.ssl.*`（PKCS12/JKS，凭据走环境变量），注意与反代二选一。
3. App 侧**三处**同步：`android/gradle.properties` 的 `readyGymServiceBaseUrl` 改 `https://…`；`app/src/main/res/xml/network_security.xml` 删掉 cleartext 的 `domain-config`（`base-config` 已是 `false`）；`app/src/debug/res/xml/debug_network_security.xml` 同步。
4. 重新出包 → 上传 → 验证 `/api/health`、注册、语音、GIF 四条链路。

未完成前，Bearer token、语音 PCM、反馈联系方式与 AI 计划内容都是**明文过公网**，不要当作机密传输。

## 正式签名切换（P0 待办，需要签名轮换决策）

`android/app/build.gradle.kts` 已准备开关：`READYGYM_KEYSTORE_FILE`、`READYGYM_KEYSTORE_PASSWORD`、`READYGYM_KEY_ALIAS`、`READYGYM_KEY_PASSWORD` 四项齐全且 keystore 文件存在时，release 自动用正式签名；否则保持 debug。

```bash
keytool -genkeypair -v -keystore readygym-release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias readygym
# keystore 放在仓库外（*.jks 已被 .gitignore 排除），设好 4 个环境变量后：
./gradlew :app:signingReport   # 确认 release 用新 Config
```

**不要直接发版**：换签名后老用户签名不一致，Android 会拒绝覆盖安装。需要走 APK 签名密钥轮换（v3 signing lineage：用旧 debug key 授权新 key，让系统允许升级），或通知用户卸载重装并在过渡期保留旧下载入口。参数以 Android `apksigner rotate` 官方文档为准，本仓库不代执行。

## 部署脚本

分片上传脚本 `.tmp-tools/deploy_chunked.py`（2MB/块 + md5 + 自动重启 + 健康检查）被 `.gitignore` 排除，不在公开仓库里。新环境需要它时，按以下契约重建：读 `READYGYM_VPS_HOST` / `READYGYM_VPS_SSH_PORT` / `READYGYM_VPS_USER` / `READYGYM_VPS_PASSWORD`（或 SSH 密钥），把 `server/build/libs/*.jar` 分片上传到 `/opt/readygym/`，校验 md5，重启 `readygym`，最后轮询 `/api/health`。**SFTP 整包直传在这台机器上经常断线，不要用。**

## 监控现状

当前没有访问日志、Actuator 与结构化指标；Spring 默认不打请求日志，**「日志里没有」不代表请求没到**。排障见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。后续可加 `spring-boot-starter-actuator`（仅 health/info，管理端口隔离）与 `CommonsRequestLoggingFilter`（务必过滤 body / `Authorization` / `X-Admin-Token`）。
