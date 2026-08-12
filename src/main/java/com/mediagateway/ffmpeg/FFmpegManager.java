package com.mediagateway.ffmpeg;

import com.mediagateway.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FFmpeg 进程管理器（每个流一个实例）
 *
 * 使用 Apache Commons Exec 管理 FFmpeg 子进程：
 * 从摄像头拉取 RTSP 流，转码输出为本地 HLS 切片，
 * 由 Spring Boot 静态资源服务通过 HTTP 提供播放。
 */
@Component
@Slf4j
public class FFmpegManager {

    private final GatewayProperties.FFmpegConfig config;
    private final String hlsOutputDir;
    private final int hlsTime;
    private final int hlsListSize;

    /**
     * streamId → FFmpegProcess
     */
    private final Map<String, FFmpegProcess> processes = new ConcurrentHashMap<>();

    public FFmpegManager(GatewayProperties properties) {
        this.config = properties.getFfmpeg();
        this.hlsOutputDir = properties.getHls().getOutputDir();
        this.hlsTime = properties.getHls().getTime();
        this.hlsListSize = properties.getHls().getListSize();
    }

    /**
     * 获取或创建 FFmpegProcess
     */
    private FFmpegProcess getOrCreateProcess(String streamId) {
        return processes.computeIfAbsent(streamId,id -> new FFmpegProcess(config, id));
    }

    /**
     * 启动 HLS
     */
    public boolean startHls(String streamId,String sourceRtsp,String resolution,String bitrate) {
        FFmpegProcess process = getOrCreateProcess(streamId);
        Path outputDir = Paths.get(hlsOutputDir, streamId);

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            log.error("创建 HLS 输出目录失败: {}",outputDir,e);
            return false;
        }

        String outputPath = outputDir.resolve("index.m3u8").toString();
        return process.startHls(sourceRtsp,outputPath,resolution,bitrate,hlsTime,hlsListSize);
    }

    /**
     * FLV 推流
     */
    public void streamFlv(String streamId,String sourceRtsp,OutputStream outputStream) throws IOException, InterruptedException {
        FFmpegProcess process = getOrCreateProcess(streamId);

        try {
            process.streamFlv(sourceRtsp,outputStream);
        } finally {
            processes.remove(streamId,process);
        }
    }

    /**
     * 停止指定流
     */
    public void stop(String streamId) {
        FFmpegProcess process = processes.get(streamId);

        if (process == null) {
            return;
        }
        process.stop();
        processes.remove(streamId,process);
    }

    /**
     * 判断指定流是否运行
     */
    public boolean isRunning(String streamId) {
        FFmpegProcess process = processes.get(streamId);
        return process != null && process.isRunning();
    }

    /**
     * 获取进程
     */
    public FFmpegProcess getProcess(String streamId) {
        return processes.get(streamId);
    }

    /**
     * 解析 FFmpeg 路径
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

    public void stopAll() {
        log.info("停止所有 FFmpeg 进程，数量={}", processes.size());
        for (String streamId : new ArrayList<>(processes.keySet())) {
            try {
                stop(streamId);
            } catch (Exception e) {
                log.warn("停止 FFmpeg 失败: streamId={}", streamId, e);
            }
        }
        processes.clear();
    }
}
