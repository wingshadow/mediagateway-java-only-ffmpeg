@echo off
chcp 65001 >nul
setlocal

:: 兼容性说明：
:: 目标系统为 Windows 7 时，必须使用 NSSM 2.24（最后一个官方支持 Win7 的版本）。
:: NSSM 2.25 及更高版本不再支持 Windows 7/2008R2。
:: 下载地址：https://nssm.cc/release/nssm-2.24.zip

set SERVICE_NAME=MediaGateway
set NSSM_PATH=%~dp0nssm.exe

echo ==========================================
echo  MediaGateway Windows 服务卸载脚本
echo  适用系统：Windows 7 / Server 2008 R2 及以上
echo ==========================================

if not exist "%NSSM_PATH%" (
    echo [错误] 未找到 nssm.exe，请将其与本脚本放在同一目录：%~dp0
    echo [提示] Windows 7 请下载 nssm-2.24：https://nssm.cc/release/nssm-2.24.zip
    pause
    exit /b 1
)

:: 停止服务
sc query %SERVICE_NAME% >nul 2>&1
if %errorlevel% == 0 (
    echo 正在停止服务 %SERVICE_NAME%...
    net stop %SERVICE_NAME% >nul 2>&1
    timeout /t 2 /nobreak >nul
) else (
    echo 服务 %SERVICE_NAME% 不存在，无需停止。
)

:: 删除服务
echo 正在删除服务 %SERVICE_NAME%...
"%NSSM_PATH%" remove %SERVICE_NAME% confirm

echo.
echo [成功] 服务 %SERVICE_NAME% 已卸载。
pause
