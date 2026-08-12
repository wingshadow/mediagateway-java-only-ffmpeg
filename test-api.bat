@echo off
chcp 65001 >nul
setlocal

:: 测试 MediaGateway API：添加指定 RTSP 地址的摄像头流

set API_BASE=http://127.0.0.1:9080
set RTSP_MAIN=rtsp://admin:rykj2808@192.168.8.88:554/stream1&channel=1
set RTSP_SUB=rtsp://admin:rykj2808@192.168.8.88:554/stream2&channel=1

echo ==========================================
echo  MediaGateway API 测试脚本
echo ==========================================

:: 检查 curl 是否可用
where curl >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未找到 curl 命令，请使用 Windows 10 及以上系统，或自行安装 curl。
    pause
    exit /b 1
)

echo.
echo [1/3] 添加摄像头流...
curl -s -X POST %API_BASE%/api/stream/add ^
    -H "Content-Type: application/json" ^
    -d "{\"streams\":[{\"name\":\"camera_8_88\",\"rtsp\":\"%RTSP_MAIN%\",\"rtsp_sub\":\"%RTSP_SUB%\"}]}" ^
    | findstr /i "streamId hls transcoding"

echo.
echo [2/3] 列出所有流...
curl -s -X GET %API_BASE%/api/stream/list

echo.
echo.
echo [3/3] 查看该流状态...
curl -s -X GET %API_BASE%/api/stream/camera_8_88/status

echo.
echo.
echo 如果以上返回了 hls 播放地址，可用浏览器或 VLC 打开：
echo   %API_BASE%/hls/camera_8_88/index.m3u8
echo.
pause
