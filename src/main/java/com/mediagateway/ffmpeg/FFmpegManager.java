package com.mediagateway.ffmpeg;

import com.mediagateway.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.LogOutputStream;
import org.apache.commons.exec.PumpStreamHandler;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FFmpeg 进程管理器（每个流一个实例）
 *
 * 使用 Apache Commons Exec 管理 FFmpeg 子进程：
 * 从摄像头拉取 RTSP 流，转码输出为本地 HLS 切片，
 * 由 Spring Boot 静态资源服务通过 HTTP 提供播放。
 */
@Slf4j
public class FFmpegManager {

    private final GatewayProperties.FFmpegConfig config;
    private final String hlsOutputDir;
    private final int hlsTime;
    private final int hlsListSize;
    private ExecuteWatchdog watchdog;
    private DefaultExecuteResultHandler resultHandler;
    private String streamId;

    public FFmpegManager(GatewayProperties.FFmpegConfig config, String hlsOutputDir, int hlsTime, int hlsListSize) {
        this.config = config;
        this.hlsOutputDir = hlsOutputDir;
        this.hlsTime = hlsTime;
        this.hlsListSize = hlsListSize;
    }

    /**
     * 解析 FFmpeg 可执行文件路径为绝对路径
     */
    public static String resolveBinPath(String binPath) {
        Path path = Paths.get(binPath);
        if (path.isAbsolute() && path.toFile().exists()) {
            return path.toString();
        }
        String baseDir = System.getProperty("user.dir");
        Path resolved = Paths.get(baseDir, binPath);
        if (resolved.toFile().exists()) {
            return resolved.toString();
        }
        return binPath;
    }

    /**
     * 检查 FFmpeg 是否可用
     */
    public static boolean isAvailable(String binPath) {
        String resolved = resolveBinPath(binPath);
        return new File(resolved).isFile();
    }

    /**
     * 启动 FFmpeg 拉流并输出 HLS
     *
     * @param streamId   流 ID
     * @param sourceRtsp 源 RTSP 地址
     * @param resolution 目标分辨率，为 null 时使用配置默认值
     * @param bitrate    目标码率，为 null 时使用配置默认值
     * @return 是否启动成功
     */
    public synchronized boolean start(String streamId, String sourceRtsp, String resolution, String bitrate) {
        if (isRunning()) {
            log.warn("FFmpeg 已在运行: streamId={}", streamId);
            return true;
        }

        this.streamId = streamId;
        String res = resolution != null ? resolution : config.getResolution();
        String br = bitrate != null ? bitrate : config.getBitrate();

        // 创建 HLS 输出目录
        Path outputDir = Paths.get(hlsOutputDir, streamId);
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            log.error("创建 HLS 输出目录失败: {}", outputDir, e);
            return false;
        }

        String outputPath = outputDir.resolve("index.m3u8").toString();

        // 解析 FFmpeg 绝对路径
        String binPath = resolveBinPath(config.getBinPath());

        // 构建 FFmpeg 命令
        List<String> cmd = new ArrayList<>();
        cmd.add(binPath);

        // RTSP 连接优化：使用 TCP 传输，避免 UDP 丢包导致花屏
        cmd.add("-rtsp_transport");
        cmd.add("tcp");

        // 降低延迟：减少分析时长
        cmd.add("-analyzeduration");
        cmd.add("1000000");
        cmd.add("-probesize");
        cmd.add("524288");

        cmd.add("-i");
        cmd.add(sourceRtsp);

        boolean isCopy = "copy".equalsIgnoreCase(config.getVideoCodec());

        if (isCopy) {
            // copy 模式：直接复制音视频流，不重新编码
            cmd.add("-c:v");
            cmd.add("copy");
            cmd.add("-c:a");
            cmd.add("copy");
        } else {
            // 转码模式
            cmd.add("-c:v");
            cmd.add(config.getVideoCodec());
            cmd.add("-s");
            cmd.add(res);
            cmd.add("-b:v");
            cmd.add(br);
            cmd.add("-preset");
            cmd.add(config.getPreset());
            cmd.add("-c:a");
            cmd.add(config.getAudioCodec());
            cmd.add("-b:a");
            cmd.add(config.getAudioBitrate());
        }

        cmd.add("-f");
        cmd.add("hls");
        cmd.add("-hls_time");
        cmd.add(String.valueOf(hlsTime));
        cmd.add("-hls_list_size");
        cmd.add(String.valueOf(hlsListSize));
        cmd.add("-hls_flags");
        cmd.add("delete_segments+append_list");
        cmd.add(outputPath);

        log.info("启动 FFmpeg HLS {}: streamId={}, source={}, output={}, codec={}, resolution={}, bitrate={}",
                isCopy ? "复制" : "转码", streamId, sourceRtsp, outputPath, config.getVideoCodec(), res, br);

        try {
            // 使用 Apache Commons Exec 构建命令行
            CommandLine cmdLine = new CommandLine(binPath);
            cmdLine.addArguments(cmd.subList(1, cmd.size()).toArray(new String[0]), false);

            DefaultExecutor executor = DefaultExecutor.builder().get();
            executor.setExitValues(null); // 不检查退出码

            watchdog = ExecuteWatchdog.builder().setTimeout(Duration.ofMillis(ExecuteWatchdog.INFINITE_TIMEOUT)).get();
            executor.setWatchdog(watchdog);

            // stdout 和 stderr 都输出到日志（逐行）
            LogOutputStream logOut = new LogOutputStream() {
                @Override
                protected void processLine(String line, int logLevel) {
                    log.info("[FFmpeg:{}] {}", streamId, line);
                }
            };
            LogOutputStream logErr = new LogOutputStream() {
                @Override
                protected void processLine(String line, int logLevel) {
                    log.info("[FFmpeg:{}] {}", streamId, line);
                }
            };
            PumpStreamHandler streamHandler = new PumpStreamHandler(logOut, logErr);
            executor.setStreamHandler(streamHandler);

            // 异步执行
            resultHandler = new DefaultExecuteResultHandler();
            executor.execute(cmdLine, resultHandler);

            // 等待一小段时间检查是否立即退出
            Thread.sleep(1000);
            if (!isRunning()) {
                log.error("FFmpeg 启动后立即退出: streamId={}", streamId);
                watchdog = null;
                resultHandler = null;
                return false;
            }

            log.info("FFmpeg HLS 转码启动成功: streamId={}", streamId);
            return true;
        } catch (IOException | InterruptedException e) {
            log.error("FFmpeg 启动失败: streamId={}", streamId, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            watchdog = null;
            resultHandler = null;
            return false;
        }
    }

    /**
     * 停止 FFmpeg 转码进程
     */
    public synchronized void stop() {
        if (watchdog == null) {
            return;
        }

        log.info("停止 FFmpeg 转码: streamId={}", streamId);
        watchdog.destroyProcess();

        // 等待进程退出（最多5秒）
        try {
            if (resultHandler != null) {
                long deadline = System.currentTimeMillis() + 5000;
                while (!resultHandler.hasResult() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }
                if (!resultHandler.hasResult()) {
                    log.warn("FFmpeg 进程未在5秒内退出: streamId={}", streamId);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        watchdog = null;
        resultHandler = null;
        log.info("FFmpeg 转码已停止: streamId={}", streamId);
    }

    /**
     * 检查 FFmpeg 是否正在运行
     */
    public boolean isRunning() {
        return watchdog != null && watchdog.isWatching();
    }

    /**
     * 获取 FFmpeg 状态
     */
    public Map<String, Object> getStatus() {
        boolean running = isRunning();
        Map<String, Object> status = new HashMap<>();
        status.put("running", running);
        status.put("pid", null);
        return status;
    }

    /**
     * 获取该流的 HLS 播放地址（相对路径）
     */
    public String getHlsPath() {
        return "/hls/" + streamId + "/index.m3u8";
    }
}