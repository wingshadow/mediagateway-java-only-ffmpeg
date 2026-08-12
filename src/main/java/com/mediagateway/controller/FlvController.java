package com.mediagateway.controller;

import com.mediagateway.config.GatewayProperties;
import com.mediagateway.ffmpeg.FFmpegManager;
import com.mediagateway.model.ApiResponse;
import com.mediagateway.service.StreamService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.LogOutputStream;
import org.apache.commons.exec.PumpStreamHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.servlet.http.HttpServletResponse;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FLV 按需推流控制器
 * <p>
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
     * <p>
     * 请求体示例：
     * {
     * "name": "camera1",
     * "rtsp": "rtsp://admin:123456@192.168.1.100:554/Streaming/Channels/101",
     * "rtsp_sub": "rtsp://admin:123456@192.168.1.100:554/Streaming/Channels/102"
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
     * <p>
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
            AtomicBoolean clientDisconnected = new AtomicBoolean(false);
            ExecuteWatchdog watchdog = null;
            Thread disconnectWatcher = null;

            try {
                CommandLine cmdLine = new CommandLine(binPath);
                cmdLine.addArguments(cmd.subList(1, cmd.size()).toArray(new String[0]), false);

                DefaultExecutor executor = DefaultExecutor.builder().get();
                executor.setExitValues(null);
                // 看门狗
                watchdog = ExecuteWatchdog.builder()
                        .setTimeout(Duration.ofMillis(ExecuteWatchdog.INFINITE_TIMEOUT))
                        .get();

                executor.setWatchdog(watchdog);

                OutputStream nonClosingOut = new FilterOutputStream(outputStream) {
                    @Override
                    public void close() throws IOException {
                        flush();
                    }
                };

                PumpStreamHandler streamHandler = new PumpStreamHandler(
                        nonClosingOut,
                        new LogOutputStream() {
                            @Override
                            protected void processLine(String line, int logLevel) {
                                log.info("[FFmpeg-FLV:{}] {}", streamId, line);
                            }
                        });

                executor.setStreamHandler(streamHandler);

                DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();

                executor.execute(cmdLine, resultHandler);

                final ExecuteWatchdog processWatchdog = watchdog;

                disconnectWatcher = new Thread(() -> {
                    while (!clientDisconnected.get()) {
                        try {
                            Thread.sleep(2000);

                            if (clientDisconnected.get()) {
                                break;
                            }

                            outputStream.flush();

                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;

                        } catch (IOException e) {
                            if (clientDisconnected.compareAndSet(false, true)) {
                                log.info("检测到客户端断开连接: streamId={}", streamId);
                                processWatchdog.destroyProcess();
                            }
                            break;
                        }
                    }
                }, "flv-disconnect-watcher-" + streamId);

                disconnectWatcher.setDaemon(true);
                disconnectWatcher.start();

                resultHandler.waitFor();

            } catch (IOException e) {
                log.info("FLV 推流结束: streamId={}", streamId);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("FLV 推流被中断: streamId={}", streamId);

            } finally {
                clientDisconnected.set(true);

                if (disconnectWatcher != null) {
                    disconnectWatcher.interrupt();
                }

                if (watchdog != null) {
                    watchdog.destroyProcess();
                }

                log.info("FFmpeg FLV 进程已停止: streamId={}", streamId);
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/x-flv"))
                .body(body);
    }
}