package com.mediagateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MediaGateway 配置属性（FFmpeg-only 模式）
 */
@Data
@Component
@ConfigurationProperties(prefix = "mediagateway")
public class GatewayProperties {

    private StreamConfig stream = new StreamConfig();
    private FFmpegConfig ffmpeg = new FFmpegConfig();
    private HlsConfig hls = new HlsConfig();
    private FlvConfig flv = new FlvConfig();

    @Data
    public static class StreamConfig {
        /** 是否按需拉流（暂未使用，保留兼容性） */
        private boolean onDemand = true;
        private AdaptiveConfig adaptive = new AdaptiveConfig();
    }

    @Data
    public static class AdaptiveConfig {
        /** 是否启用自适应 */
        private boolean enabled = true;
        /** 检查间隔（秒） */
        private int checkInterval = 10;
        /** 失败阈值 */
        private int failThreshold = 3;
        /** 成功阈值 */
        private int successThreshold = 5;
    }

    @Data
    public static class FFmpegConfig {
        /** 是否默认启用 FFmpeg 转码 */
        private boolean enabled = true;
        /** FFmpeg 可执行文件路径（工程内置） */
        private String binPath = "ffmpeg/ffmpeg.exe";
        /** 视频编码器 */
        private String videoCodec = "libx264";
        /** 目标分辨率 */
        private String resolution = "640x480";
        /** 目标码率 */
        private String bitrate = "500k";
        /** 编码预设 */
        private String preset = "ultrafast";
        /** 音频编码器 */
        private String audioCodec = "aac";
        /** 音频码率 */
        private String audioBitrate = "64k";
    }

    @Data
    public static class HlsConfig {
        /** HLS 切片本地输出目录 */
        private String outputDir = "hls";
        /** HLS 播放地址前缀 */
        private String urlPrefix = "/hls";
        /** HLS 播放基础 URL，为空时返回相对路径 */
        private String baseUrl = "";
        /** 切片时长（秒） */
        private int time = 2;
        /** m3u8 播放列表保留切片数量 */
        private int listSize = 5;
    }

    @Data
    public static class FlvConfig {
        /** 是否启用 FLV 按需推流 */
        private boolean enabled = true;
    }
}
