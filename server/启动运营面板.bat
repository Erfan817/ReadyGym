@echo off
chcp 65001 >nul
cd /d %~dp0

rem 本地一键启动：设好 Admin Token，起服务器，等健康检查通过后自动打开运营面板。
rem 公网/生产环境必须先 set READYGYM_ADMIN_TOKEN=<随机值> 再运行；未设置时脚本生成一次性本地随机 token。

if "%READYGYM_ADMIN_TOKEN%"=="" set "READYGYM_ADMIN_TOKEN=local-%RANDOM%-%RANDOM%"

echo.
echo   ==============================================
echo    ReadyGym 运营面板（本地）
echo    面板地址: http://localhost:8080/admin.html
echo    Admin Token: %READYGYM_ADMIN_TOKEN%
echo    服务器就绪后浏览器会自动打开面板
echo    关闭本窗口即停止服务器
echo   ==============================================
echo.

start "" powershell -NoProfile -Command "do { $ok = $true; try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 http://localhost:8080/api/health | Out-Null } catch { $ok = $false; Start-Sleep -Seconds 2 } } while (-not $ok); Start-Process 'http://localhost:8080/admin.html'"

call gradlew.bat bootRun -PreadyGymAdminToken=%READYGYM_ADMIN_TOKEN%
