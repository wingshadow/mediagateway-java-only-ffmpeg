package com.mediagateway.controller;

import com.mediagateway.config.GatewayProperties;
import com.mediagateway.ffmpeg.FFmpegManager;
import com.mediagateway.model.ApiResponse;
import com.mediagateway.service.StreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

/**
 * FLV 按需推流控制器
 * <p>
 * Controller 只负责 HTTP 请求和响应，
 * FFmpeg 进程由 FFmpegManager 统一管理。
 */
@Slf4j
@RestController
@RequestMapping("/api/flv")
public class FlvController {

    private final StreamService streamService;
    private final GatewayProperties properties;
    private final FFmpegManager ffmpegManager;

    public FlvController(
            StreamService streamService,
            GatewayProperties properties,
            FFmpegManager ffmpegManager) {

        this.streamService = streamService;
        this.properties = properties;
        this.ffmpegManager = ffmpegManager;
    }

    /**
     * 添加 FLV 流
     */
    @PostMapping("/add")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addFlvStream(
            @RequestBody Map<String, Object> body) {

        String name = (String) body.get("name");
        String rtsp = (String) body.get("rtsp");
        String rtspSub = (String) body.get("rtsp_sub");

        if (name == null || name.isEmpty() || rtsp == null || rtsp.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("name 和 rtsp 不能为空"));
        }

        Map<String, Object> result = streamService.addFlvStream(name, rtsp, rtspSub);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 添加 FLV 流
     */
    @GetMapping("/add")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addFlvStreamByGet(
            @RequestParam String name,
            @RequestParam String rtsp,
            @RequestParam(required = false) String rtsp_sub) {

        if (name == null || name.isEmpty() || rtsp == null || rtsp.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("name 和 rtsp 不能为空"));
        }

        Map<String, Object> result = streamService.addFlvStream(name, rtsp, rtsp_sub);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * FLV 流式播放
     * <p>
     * 客户端连接建立后，由 FFmpegManager 启动 FFmpeg：
     * <p>
     * RTSP → FFmpeg → FLV → HTTP Response
     * <p>
     * 客户端断开后，FFmpegManager 自动停止 FFmpeg。
     */
    @GetMapping("/{streamId}.flv")
    public ResponseEntity<StreamingResponseBody> streamFlv(
            @PathVariable String streamId,
            HttpServletResponse response) {

        // 检查 FLV 是否启用
        if (!properties.getFlv().isEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // 获取 RTSP 地址
        String rtspUrl = streamService.getStreamRtspUrl(streamId);

        if (rtspUrl == null) {
            log.warn("FLV 请求的流不存在: streamId={}", streamId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // 设置响应头
        response.setContentType("video/x-flv");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("Access-Control-Allow-Origin", "*");

        log.info("收到 FLV 播放请求: streamId={}", streamId);

        StreamingResponseBody body = outputStream -> {
            try {
                ffmpegManager.streamFlv(streamId, rtspUrl, outputStream);
            } catch (IOException e) {
                log.info("FLV 客户端连接结束: streamId={}", streamId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("FLV 推流被中断: streamId={}", streamId);
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/x-flv"))
                .body(body);
    }
}