package com.mediagateway.ffmpeg;

import com.mediagateway.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
 * 管理 FFmpeg 子进程：从摄像头拉取 RTSP 流，转码输出为本地 HLS 切片，
 * 由 Spring Boot 静态资源服务通过 HTTP 提供播放。
 */
@Slf4j
public class FFmpegManager {

    private final GatewayProperties.FFmpegConfig config;
    private final String hlsOutputDir;
    private final int hlsTime;
    private final int hlsListSize;
    private Process process;
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
     * Java 8 兼容的进程存活检测（替代 Process.isAlive()）
     */
    private static boolean isProcessAlive(Process process) {
        if (process == null) {
            return false;
        }
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
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
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("PATH", System.getenv("PATH"));
            process = pb.start();

            // 启动日志读取线程
            Thread logThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[FFmpeg:{}] {}", streamId, line);
                    }
                } catch (IOException e) {
                    // 进程结束，忽略
                }
            }, "ffmpeg-log-" + streamId);
            logThread.setDaemon(true);
            logThread.start();

            // 等待一小段时间检查是否立即退出
            Thread.sleep(1000);
            if (!isProcessAlive(process)) {
                int exitCode = process.exitValue();
                log.error("FFmpeg 启动后立即退出: streamId={}, exitCode={}", streamId, exitCode);
                process = null;
                return false;
            }

            log.info("FFmpeg HLS 转码启动成功: streamId={}", streamId);
            return true;
        } catch (IOException | InterruptedException e) {
            log.error("FFmpeg 启动失败: streamId={}", streamId, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            process = null;
            return false;
        }
    }

    /**
     * 停止 FFmpeg 转码进程
     */
    public synchronized void stop() {
        if (process == null || !isProcessAlive(process)) {
            process = null;
            return;
        }

        log.info("停止 FFmpeg 转码: streamId={}", streamId);
        process.destroy();
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("FFmpeg 进程未响应 destroy，执行 destroyForcibly: streamId={}", streamId);
                process.destroyForcibly();
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
        log.info("FFmpeg 转码已停止: streamId={}", streamId);
    }

    /**
     * 检查 FFmpeg 是否正在运行
     */
    public boolean isRunning() {
        return process != null && isProcessAlive(process);
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
