package com.mediagateway.service;

import com.mediagateway.config.GatewayProperties;
import com.mediagateway.ffmpeg.FFmpegManager;
import com.mediagateway.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

/**
 * 流管理服务
 *
 * <p>
 * StreamService 只负责：
 * 1. 流信息管理
 * 2. 主码流/子码流切换
 * 3. 自适应策略
 * 4. 调用 FFmpegManager 管理 FFmpeg
 *
 * <p>
 * FFmpegManager 统一负责：
 * 1. FFmpeg 进程创建
 * 2. FFmpeg 进程停止
 * 3. FFmpeg 进程状态
 * 4. HLS
 * 5. FLV
 */
@Slf4j
@Service
public class StreamService {

    private final GatewayProperties properties;

    /**
     * 全局 FFmpeg 管理器
     *
     * <p>
     * 一个 FFmpegManager 管理多个 streamId 对应的 FFmpeg 进程。
     */
    private final FFmpegManager ffmpegManager;

    /**
     * 流缓存：streamId -> 流信息
     */
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    /**
     * 自适应状态：
     *
     * streamId -> {
     *     failCount,
     *     successCount,
     *     currentType
     * }
     */
    private final Map<String, Map<String, Object>> adaptiveState = new ConcurrentHashMap<>();

    /**
     * 自适应检查线程
     */
    private ScheduledExecutorService adaptiveExecutor;

    public StreamService(GatewayProperties properties, FFmpegManager ffmpegManager) {
        this.properties = properties;
        this.ffmpegManager = ffmpegManager;
    }

    @PostConstruct
    public void init() {
        log.info("StreamService 初始化开始");

        if (properties.getStream().getAdaptive().isEnabled()) {
            startAdaptiveLoop();
        }
    }

    @PreDestroy
    public void destroy() {
        if (adaptiveExecutor != null) {
            adaptiveExecutor.shutdownNow();
        }

        // FFmpegManager 统一停止所有 FFmpeg
        try {
            ffmpegManager.stopAll();
        } catch (Exception e) {
            log.warn("停止所有 FFmpeg 失败", e);
        }

        log.info("StreamService 已销毁");
    }

    /**
     * 添加流
     *
     * <p>
     * 默认使用 FFmpeg 转码输出 HLS；
     * 如果关闭 FFmpeg，则只缓存流信息。
     */
    public List<Map<String, Object>> addStreams(List<Map<String, Object>> streams) {
        List<Map<String, Object>> results = new ArrayList<>();

        for (Map<String, Object> stream : streams) {
            String name = (String) stream.get("name");
            String rtsp = (String) stream.get("rtsp");
            String rtspSub = (String) stream.get("rtsp_sub");
            String streamId = buildStreamId(name);

            if (cache.containsKey(streamId)) {
                log.info("同名流已存在，先停止并清理旧流: streamId={}", streamId);
                deleteStream(streamId);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> ffmpegParams = (Map<String, Object>) stream.get("ffmpeg");

            boolean ffmpegEnabled = properties.getFfmpeg().isEnabled();

            if (ffmpegParams != null && ffmpegParams.containsKey("enabled")) {
                ffmpegEnabled = Boolean.TRUE.equals(ffmpegParams.get("enabled"));
            }

            try {
                String resolution = ffmpegParams != null && ffmpegParams.get("resolution") != null
                        ? (String) ffmpegParams.get("resolution") : null;

                String bitrate = ffmpegParams != null && ffmpegParams.get("bitrate") != null
                        ? (String) ffmpegParams.get("bitrate") : null;

                if (ffmpegEnabled) {
                    boolean started = ffmpegManager.startHls(streamId, rtsp, resolution, bitrate);

                    if (!started) {
                        log.warn("FFmpeg HLS 启动失败: streamId={}", streamId);
                        ffmpegEnabled = false;
                    }
                }

                // 缓存流信息
                Map<String, Object> info = new ConcurrentHashMap<>();
                info.put("name", name);
                info.put("rtsp", rtsp);
                info.put("rtsp_sub", rtspSub);
                info.put("streamId", streamId);
                info.put("transcoding", ffmpegEnabled);
                info.put("currentType", ffmpegEnabled ? "transcode" : "main");

                cache.put(streamId, info);

                // 初始化自适应状态
                Map<String, Object> state = new ConcurrentHashMap<>();
                state.put("failCount", 0);
                state.put("successCount", 0);
                state.put("currentType", ffmpegEnabled ? "transcode" : "main");

                adaptiveState.put(streamId, state);

                Map<String, Object> result = new HashMap<>();
                result.put("streamId", streamId);
                result.put("name", name);
                result.put("rtsp", rtsp);
                result.put("rtsp_sub", rtspSub);
                result.put("hls", buildHlsUrl(streamId));
                result.put("webrtc", null);
                result.put("http_flv", buildFlvUrl(streamId));
                result.put("transcoding", ffmpegEnabled);

                results.add(result);

                log.info("添加流成功: streamId={}, name={}, transcoding={}",
                        streamId, name, ffmpegEnabled);

            } catch (Exception e) {
                log.error("添加流失败: name={}", name, e);

                Map<String, Object> errResult = new HashMap<>();
                errResult.put("streamId", streamId);
                errResult.put("name", name);
                errResult.put("error", e.getMessage());

                results.add(errResult);
            }
        }

        return results;
    }

    /**
     * 删除流
     */
    public boolean deleteStream(String streamId) {

        // 由 FFmpegManager 统一停止该流对应的 FFmpeg
        ffmpegManager.stop(streamId);

        cache.remove(streamId);
        adaptiveState.remove(streamId);

        // 清理 HLS 输出目录
        cleanupHlsDirectory(streamId);

        log.info("删除流: streamId={}", streamId);

        return true;
    }

    /**
     * 获取流状态
     */
    public Map<String, Object> getStreamStatus(String streamId) {
        Map<String, Object> info = cache.get(streamId);

        if (info == null) {
            return null;
        }

        Map<String, Object> status = new HashMap<>();

        status.put("streamId", streamId);
        status.put("name", info.get("name"));
        status.put("type", info.get("currentType") != null
                ? info.get("currentType") : "main");
        status.put("rtsp", info.get("rtsp"));
        status.put("rtsp_sub", info.get("rtsp_sub"));
        status.put("hls", buildHlsUrl(streamId));
        status.put("webrtc", null);
        status.put("http_flv", buildFlvUrl(streamId));

        // 状态统一由 FFmpegManager 查询
        status.put("transcoding", ffmpegManager.isRunning(streamId));

        Map<String, Object> state = adaptiveState.get(streamId);

        if (state != null) {
            Map<String, Object> adaptive = new HashMap<>();

            adaptive.put("failCount", state.get("failCount"));
            adaptive.put("successCount", state.get("successCount"));

            status.put("adaptive", adaptive);
        }

        return status;
    }

    /**
     * 列出所有流
     */
    public List<Map<String, Object>> listStreams() {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map.Entry<String, Map<String, Object>> entry : cache.entrySet()) {
            String streamId = entry.getKey();
            Map<String, Object> info = entry.getValue();

            Map<String, Object> status = new HashMap<>();

            status.put("streamId", streamId);
            status.put("name", info.get("name"));
            status.put("type", info.get("currentType") != null
                    ? info.get("currentType") : "main");
            status.put("rtsp", info.get("rtsp"));
            status.put("rtsp_sub", info.get("rtsp_sub"));
            status.put("hls", buildHlsUrl(streamId));
            status.put("webrtc", null);
            status.put("http_flv", buildFlvUrl(streamId));
            status.put("transcoding", ffmpegManager.isRunning(streamId));

            result.add(status);
        }

        return result;
    }

    /**
     * 切换码流（主码流 ↔ 子码流）
     */
    public Map<String, Object> switchStream(String streamId, String target) {

        Map<String, Object> info = cache.get(streamId);

        if (info == null) {
            throw new RuntimeException("流不存在: " + streamId);
        }

        String rtsp;

        switch (target) {
            case "main":
                rtsp = (String) info.get("rtsp");
                break;

            case "sub":
                rtsp = (String) info.get("rtsp_sub");

                if (rtsp == null || rtsp.isEmpty()) {
                    throw new RuntimeException("子码流地址未配置: " + streamId);
                }

                break;

            default:
                throw new RuntimeException("不支持的切换目标: " + target);
        }

        /*
         * 重要：
         *
         * 不再：
         *
         * new FFmpegManager(...)
         *
         * 直接让统一的 FFmpegManager 替换该 streamId 的进程。
         */
        ffmpegManager.stop(streamId);

        boolean started = ffmpegManager.startHls(streamId, rtsp, null, null);

        if (!started) {
            throw new RuntimeException("切换码流后 FFmpeg 启动失败: " + streamId);
        }

        // 更新流状态
        info.put("currentType", target);
        info.put("transcoding", true);

        Map<String, Object> state = adaptiveState.get(streamId);

        if (state != null) {
            state.put("failCount", 0);
            state.put("successCount", 0);
            state.put("currentType", target);
        }

        Map<String, Object> result = new HashMap<>();

        result.put("streamId", streamId);
        result.put("type", target);
        result.put("rtsp", rtsp);
        result.put("hls", buildHlsUrl(streamId));
        result.put("webrtc", null);
        result.put("http_flv", buildFlvUrl(streamId));
        result.put("transcoding", true);
        result.put("message", "已切换到" + ("main".equals(target) ? "主码流" : "子码流"));

        log.info("切换码流: streamId={}, target={}", streamId, target);

        return result;
    }

    /**
     * 动态开启/关闭转码
     */
    public Map<String, Object> toggleTranscode(
            String streamId,
            boolean enabled,
            String resolution,
            String bitrate) {

        Map<String, Object> info = cache.get(streamId);

        if (info == null) {
            throw new RuntimeException("流不存在: " + streamId);
        }

        String currentType = (String) info.get("currentType");

        String rtsp;

        if ("sub".equals(currentType)) {
            rtsp = (String) info.get("rtsp_sub");
        } else {
            rtsp = (String) info.get("rtsp");
        }

        /*
         * 这里仍然由 FFmpegManager 负责实际进程。
         */
        ffmpegManager.stop(streamId);

        if (enabled) {

            boolean started = ffmpegManager.startHls(
                    streamId,
                    rtsp,
                    resolution,
                    bitrate);

            if (!started) {
                throw new RuntimeException("FFmpeg 启动失败: " + streamId);
            }

            info.put("transcoding", true);
            info.put("currentType", "transcode");

        } else {

            /*
             * 当前架构下 HLS 本身就是 FFmpeg 输出，
             * 所以“关闭转码”并不意味着停止 FFmpeg。
             *
             * 如果继续使用 HLS，就仍然需要 FFmpeg。
             *
             * 因此这里恢复主码流，但使用配置中的默认参数。
             */
            rtsp = (String) info.get("rtsp");

            boolean started = ffmpegManager.startHls(
                    streamId,
                    rtsp,
                    null,
                    null);

            if (!started) {
                throw new RuntimeException("FFmpeg 启动失败: " + streamId);
            }

            info.put("transcoding", true);
            info.put("currentType", "main");
        }

        Map<String, Object> state = adaptiveState.get(streamId);

        if (state != null) {
            state.put("failCount", 0);
            state.put("successCount", 0);
            state.put("currentType", info.get("currentType"));
        }

        Map<String, Object> result = new HashMap<>();

        result.put("streamId", streamId);
        result.put("transcoding", true);
        result.put("hls", buildHlsUrl(streamId));
        result.put("webrtc", null);
        result.put("http_flv", buildFlvUrl(streamId));
        result.put("message", enabled
                ? "已开启转码"
                : "已关闭转码并恢复主码流");

        log.info("转码切换: streamId={}, enabled={}", streamId, enabled);

        return result;
    }

    // ==================== 自适应检测 ====================

    private void startAdaptiveLoop() {

        adaptiveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "adaptive-check");
            t.setDaemon(true);
            return t;
        });

        int interval = properties.getStream()
                .getAdaptive()
                .getCheckInterval();

        adaptiveExecutor.scheduleAtFixedRate(
                this::adaptiveCheck,
                interval,
                interval,
                TimeUnit.SECONDS);

        log.info("自适应检测已启动，间隔{}秒", interval);
    }

    private void adaptiveCheck() {

        for (Map.Entry<String, Map<String, Object>> entry : cache.entrySet()) {

            String streamId = entry.getKey();

            try {
                checkAndSwitch(streamId);
            } catch (Exception e) {
                log.error("自适应检测异常: streamId={}", streamId, e);
            }
        }
    }

    /**
     * 检查流状态并自适应切换
     */
    private void checkAndSwitch(String streamId) {

        Map<String, Object> info = cache.get(streamId);

        if (info == null) {
            return;
        }

        Map<String, Object> state = adaptiveState.get(streamId);

        if (state == null) {
            return;
        }

        boolean ready = isHlsReady(streamId);

        int failCount = (int) state.get("failCount");
        int successCount = (int) state.get("successCount");

        if (ready) {
            successCount++;
            failCount = 0;
        } else {
            failCount++;
            successCount = 0;
        }

        state.put("failCount", failCount);
        state.put("successCount", successCount);

        String currentType = (String) state.get("currentType");

        int failThreshold = properties.getStream()
                .getAdaptive()
                .getFailThreshold();

        int successThreshold = properties.getStream()
                .getAdaptive()
                .getSuccessThreshold();

        // 网络不稳定
        if (failCount >= failThreshold) {

            if ("main".equals(currentType)) {

                String rtspSub = (String) info.get("rtsp_sub");

                if (rtspSub != null && !rtspSub.isEmpty()) {

                    log.info("自适应: 主码流不稳定，切换子码流: streamId={}", streamId);

                    try {
                        switchStream(streamId, "sub");
                        return;
                    } catch (Exception e) {
                        log.warn("自适应: 切换子码流失败: streamId={}", streamId, e);
                    }
                }

            } else if ("sub".equals(currentType)) {

                log.info("自适应: 子码流不稳定，启用低分辨率转码: streamId={}", streamId);

                try {
                    toggleTranscode(
                            streamId,
                            true,
                            "320x240",
                            "256k");

                    return;

                } catch (Exception e) {
                    log.warn("自适应: 启用低分辨率转码失败: streamId={}", streamId, e);
                }
            }
        }

        // 网络恢复
        if (successCount >= successThreshold) {

            if ("transcode".equals(currentType)) {

                log.info("自适应: 网络恢复，切换回子码流: streamId={}", streamId);

                try {
                    switchStream(streamId, "sub");
                } catch (Exception e) {
                    log.warn("自适应: 切换子码流失败: streamId={}", streamId, e);
                }

            } else if ("sub".equals(currentType)) {

                log.info("自适应: 网络恢复，切换回主码流: streamId={}", streamId);

                try {
                    switchStream(streamId, "main");
                } catch (Exception e) {
                    log.warn("自适应: 切换主码流失败: streamId={}", streamId, e);
                }
            }
        }
    }

    // ==================== FLV ====================

    /**
     * 添加 FLV 流
     *
     * <p>
     * 不启动 FFmpeg。
     *
     * <p>
     * 客户端真正请求 FLV 时，由 FlvController 调用 FFmpegManager
     * 创建对应的 FLV FFmpeg 进程。
     */
    public Map<String, Object> addFlvStream(
            String name,
            String rtsp,
            String rtspSub) {

        String streamId = buildStreamId(name);

        if (cache.containsKey(streamId)) {
            log.info("同名流已存在，先清理旧的: streamId={}", streamId);
            deleteStream(streamId);
        }

        Map<String, Object> info = new ConcurrentHashMap<>();

        info.put("name", name);
        info.put("rtsp", rtsp);
        info.put("rtsp_sub", rtspSub);
        info.put("streamId", streamId);
        info.put("transcoding", false);
        info.put("currentType", "main");

        cache.put(streamId, info);

        Map<String, Object> state = new ConcurrentHashMap<>();

        state.put("failCount", 0);
        state.put("successCount", 0);
        state.put("currentType", "main");

        adaptiveState.put(streamId, state);

        Map<String, Object> result = new HashMap<>();

        result.put("streamId", streamId);
        result.put("name", name);
        result.put("rtsp", rtsp);
        result.put("rtsp_sub", rtspSub);
        result.put("hls", null);
        result.put("webrtc", null);
        result.put("http_flv", buildFlvUrl(streamId));
        result.put("transcoding", false);

        log.info("添加 FLV 流: streamId={}, name={}", streamId, name);

        return result;
    }

    /**
     * 获取当前流 RTSP 地址
     */
    public String getStreamRtspUrl(String streamId) {

        Map<String, Object> info = cache.get(streamId);

        if (info == null) {
            return null;
        }

        String currentType = (String) info.get("currentType");

        if ("sub".equals(currentType)) {
            return (String) info.get("rtsp_sub");
        }

        return (String) info.get("rtsp");
    }

    // ==================== 工具方法 ====================

    private String buildHlsUrl(String streamId) {

        String prefix = properties.getHls().getUrlPrefix();

        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }

        String path = prefix + "/" + streamId + "/index.m3u8";

        String baseUrl = properties.getHls().getBaseUrl();

        if (baseUrl != null && !baseUrl.isEmpty()) {

            baseUrl = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1)
                    : baseUrl;

            return baseUrl + path;
        }

        return path;
    }

    private String buildFlvUrl(String streamId) {

        String baseUrl = properties.getHls().getBaseUrl();

        if (baseUrl != null && !baseUrl.isEmpty()) {

            baseUrl = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1)
                    : baseUrl;

            return baseUrl + "/api/flv/" + streamId + ".flv";
        }

        return "/api/flv/" + streamId + ".flv";
    }

    private boolean isHlsReady(String streamId) {

        Path m3u8 = Paths.get(
                properties.getHls().getOutputDir(),
                streamId,
                "index.m3u8");

        if (!Files.exists(m3u8)) {
            return false;
        }

        long lastModified = m3u8.toFile().lastModified();

        return System.currentTimeMillis() - lastModified < 10000L;
    }

    private void cleanupHlsDirectory(String streamId) {

        try {

            Path hlsDir = Paths.get(
                    properties.getHls().getOutputDir(),
                    streamId);

            if (!Files.exists(hlsDir)) {
                return;
            }

            Files.walk(hlsDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            log.warn("删除 HLS 文件失败: {}", path, e);
                        }
                    });

            log.info("已清理 HLS 目录: {}", hlsDir);

        } catch (Exception e) {
            log.warn("清理 HLS 目录失败: streamId={}", streamId, e);
        }
    }

    private String buildStreamId(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_");
    }
}