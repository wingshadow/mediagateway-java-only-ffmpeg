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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FLV 按需推流控制器
 *
 * 当客户端请求 /flv/{streamId}.flv 时，启动 FFmpeg 进程将 RTSP 流
 * 转为 FLV 格式并直接通过 HTTP 响应流式传输，客户端断开后自动停止 FFmpeg。
 */
@Slf4j
@RestController
@RequestMapping("/api/flv")
public class FlvController {

    private final StreamService streamService;
    private final GatewayProperties properties;

    public FlvController(StreamService streamService, GatewayProperties properties) {
        this.streamService = streamService;
        this.properties = properties;
    }

    /**
     * 添加 FLV 流（POST 方式，JSON 请求体）
     * 直接注册流信息供 FLV 按需推流，不启动 HLS 转码。
     *
     * 请求体示例：
     * {
     *   "name": "camera1",
     *   "rtsp": "rtsp://admin:123456@192.168.1.100:554/Streaming/Channels/101",
     *   "rtsp_sub": "rtsp://admin:123456@192.168.1.100:554/Streaming/Channels/102"
     * }
     */
    @PostMapping("/add")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addFlvStream(@RequestBody Map<String, Object> body) {
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
     * 添加 FLV 流（GET 方式，URL 参数）
     * 直接注册流信息供 FLV 按需推流，不启动 HLS 转码。
     *
     * 示例：GET /flv/add?name=camera1&rtsp=rtsp://...&rtsp_sub=rtsp://...
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

    @GetMapping("/{streamId}.flv")
    public ResponseEntity<StreamingResponseBody> streamFlv(
            @PathVariable String streamId,
            HttpServletResponse response) {

        // 检查 FLV 是否启用
        if (!properties.getFlv().isEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // 获取流的 RTSP 地址
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

        // 构建 FFmpeg 命令
        GatewayProperties.FFmpegConfig ffmpegConfig = properties.getFfmpeg();
        String binPath = FFmpegManager.resolveBinPath(ffmpegConfig.getBinPath());

        List<String> cmd = new ArrayList<>();
        cmd.add(binPath);
        cmd.add("-rtsp_transport");
        cmd.add("tcp");
        cmd.add("-analyzeduration");
        cmd.add("1000000");
        cmd.add("-probesize");
        cmd.add("524288");
        cmd.add("-i");
        cmd.add(rtspUrl);

        boolean isCopy = "copy".equalsIgnoreCase(ffmpegConfig.getVideoCodec());
        if (isCopy) {
            cmd.add("-c:v");
            cmd.add("copy");
            cmd.add("-c:a");
            cmd.add("copy");
        } else {
            cmd.add("-c:v");
            cmd.add(ffmpegConfig.getVideoCodec());
            cmd.add("-s");
            cmd.add(ffmpegConfig.getResolution());
            cmd.add("-b:v");
            cmd.add(ffmpegConfig.getBitrate());
            cmd.add("-preset");
            cmd.add(ffmpegConfig.getPreset());
            cmd.add("-c:a");
            cmd.add(ffmpegConfig.getAudioCodec());
            cmd.add("-b:a");
            cmd.add(ffmpegConfig.getAudioBitrate());
        }

        cmd.add("-f");
        cmd.add("flv");
        cmd.add("pipe:1");

        log.info("启动 FFmpeg FLV 推流: streamId={}, source={}", streamId, rtspUrl);

        StreamingResponseBody body = outputStream -> {
            Process process = null;
            AtomicBoolean clientDisconnected = new AtomicBoolean(false);
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                // 不合并 stderr 到 stdout，stdout 专门用于 FLV 数据
                pb.redirectErrorStream(false);
                pb.environment().put("PATH", System.getenv("PATH"));
                process = pb.start();

                // 单独线程读取 stderr 用于日志
                final Process finalProcess = process;
                Thread stderrThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(finalProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            log.info("[FFmpeg-FLV:{}] {}", streamId, line);
                        }
                    } catch (IOException e) {
                        // 进程结束，忽略
                    }
                }, "ffmpeg-flv-log-" + streamId);
                stderrThread.setDaemon(true);
                stderrThread.start();

                // 客户端断开检测线程：定期尝试写一个空字节来检测连接是否已关闭
                final OutputStream finalOutputStream = outputStream;
                Thread disconnectWatcher = new Thread(() -> {
                    while (!clientDisconnected.get() && finalProcess.isAlive()) {
                        try {
                            Thread.sleep(2000);
                            if (clientDisconnected.get()) {
                                break;
                            }
                            // 检查连接是否还活着
                            finalOutputStream.flush();
                        } catch (InterruptedException e) {
                            break;
                        } catch (IOException e) {
                            // flush 失败说明客户端已断开
                            clientDisconnected.set(true);
                            log.info("检测到客户端断开连接: streamId={}", streamId);
                            finalProcess.destroy();
                            break;
                        }
                    }
                }, "flv-disconnect-watcher-" + streamId);
                disconnectWatcher.setDaemon(true);
                disconnectWatcher.start();

                // 将 FFmpeg stdout 管道传输到 HTTP 响应
                InputStream stdout = process.getInputStream();
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = stdout.read(buffer)) != -1) {
                    if (clientDisconnected.get()) {
                        break;
                    }
                    outputStream.write(buffer, 0, bytesRead);
                    outputStream.flush();
                }
            } catch (IOException e) {
                log.info("FLV 推流结束（客户端可能已断开）: streamId={}", streamId);
            } finally {
                clientDisconnected.set(true);
                if (process != null) {
                    process.destroy();
                    try {
                        if (!process.waitFor(3, TimeUnit.SECONDS)) {
                            process.destroyForcibly();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        process.destroyForcibly();
                    }
                }
                log.info("FFmpeg FLV 进程已停止: streamId={}", streamId);
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/x-flv"))
                .body(body);
    }
}