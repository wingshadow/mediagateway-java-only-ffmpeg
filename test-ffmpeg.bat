@echo off
chcp 65001 >nul
setlocal

:: 使用指定 RTSP 地址测试 FFmpeg 拉流并输出 HLS

set FFMPEG=ffmpeg\ffmpeg.exe
set RTSP_URL="rtsp://admin:rykj2808@192.168.8.88:554/stream2&channel=1"
set OUTPUT_DIR=hls\test_camera

echo ==========================================
echo  FFmpeg 拉流测试脚本
echo ==========================================

:: 检查 FFmpeg
if not exist "%FFMPEG%" (
    echo [错误] 未找到 %FFMPEG%，请将其放置到 ffmpeg\ffmpeg.exe
    pause
    exit /b 1
)

:: 创建输出目录
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

:: 清理旧切片，避免干扰观察
if exist "%OUTPUT_DIR%\*.ts" del /q "%OUTPUT_DIR%\*.ts" >nul 2>&1
if exist "%OUTPUT_DIR%\index.m3u8" del /q "%OUTPUT_DIR%\index.m3u8" >nul 2>&1

echo 测试地址：%RTSP_URL%
echo 输出目录：%OUTPUT_DIR%\index.m3u8
echo.
echo 正在拉流，按 Ctrl+C 停止...
echo.

"%FFMPEG%" -i %RTSP_URL% ^
    -c:v libx264 ^
    -s 640x480 ^
    -b:v 500k ^
    -preset ultrafast ^
    -c:a aac ^
    -b:a 64k ^
    -f hls ^
    -hls_time 2 ^
    -hls_list_size 5 ^
    -hls_flags delete_segments ^
    "%OUTPUT_DIR%\index.m3u8"

echo.
echo FFmpeg 已退出，退出码：%errorlevel%
echo.
echo 如果输出目录中生成了 index.m3u8 和 .ts 文件，说明拉流成功。
pause
