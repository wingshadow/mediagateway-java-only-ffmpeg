@echo off
chcp 65001 >nul
setlocal

:: 测试 MediaGateway FLV API：添加摄像头并获取 FLV 推流地址

set API_BASE=http://127.0.0.1:9080
set RTSP_MAIN=rtsp://admin:rykj2808@192.168.8.88:554/stream1&channel=1
set RTSP_SUB=rtsp://admin:rykj2808@192.168.8.88:554/stream2&channel=1
set STREAM_NAME=camera_flv_test

echo ==========================================
echo  MediaGateway FLV API 测试脚本
echo ==========================================

:: 检查 curl 是否可用
where curl >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未找到 curl 命令，请使用 Windows 10 及以上系统，或自行安装 curl。
    pause
    exit /b 1
)

echo.
echo [1/4] POST /flv/add 添加摄像头（JSON 方式）...
echo     请求体: {"name":"%STREAM_NAME%","rtsp":"%RTSP_MAIN%","rtsp_sub":"%RTSP_SUB%"}
curl -s -X POST %API_BASE%/flv/add ^
    -H "Content-Type: application/json" ^
    -d "{\"name\":\"%STREAM_NAME%\",\"rtsp\":\"%RTSP_MAIN%\",\"rtsp_sub\":\"%RTSP_SUB%\"}"

echo.
echo.
echo [2/4] GET /flv/add 添加摄像头（URL 参数方式）...
curl -s -X GET "%API_BASE%/flv/add?name=%STREAM_NAME%_get^&rtsp=%RTSP_MAIN%"

echo.
echo.
echo [3/4] 列出所有流...
curl -s -X GET %API_BASE%/api/stream/list

echo.
echo.
echo [4/4] FLV 播放地址...
echo   POST 方式: %API_BASE%/flv/%STREAM_NAME%.flv
echo   GET  方式: %API_BASE%/flv/%STREAM_NAME%_get.flv
echo.
echo 可用浏览器打开 player.html 选择 FLV 格式播放:
echo   %API_BASE%/player.html
echo.
echo 或用 ffplay / VLC 直接播放:
echo   ffplay %API_BASE%/flv/%STREAM_NAME%.flv
echo.
pause