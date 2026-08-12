package com.mediagateway.controller;

import com.mediagateway.model.*;
import com.mediagateway.service.StreamService;
import javax.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 流管理API
 */
@Slf4j
@RestController
@RequestMapping("/api/stream")
public class StreamController {

    private final StreamService streamService;

    public StreamController(StreamService streamService) {
        this.streamService = streamService;
    }

    /**
     * 添加流
     *
     * POST /api/stream/add
     * 请求体示例:
     * {
     *   "streams": [
     *     {
     *       "name": "电梯001",
     *       "rtsp": "rtsp://admin:123456@192.168.1.100:554/stream1",
     *       "rtsp_sub": "rtsp://admin:123456@192.168.1.100:554/stream2",
     *       "ffmpeg": { "enabled": true, "resolution": "640x480", "bitrate": "500k" }
     *     }
     *   ]
     * }
     */
    @PostMapping("/add")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> addStreams(
            @Valid @RequestBody AddStreamRequest request) {
        // 转换为Map列表
        List<Map<String, Object>> streamMaps = request.getStreams().stream().map(item -> {
            Map<String, Object> map = new HashMap<>();
            map.put("name", item.getName());
            map.put("rtsp", item.getRtsp());
            map.put("rtsp_sub", item.getRtspSub());
            if (item.getFfmpeg() != null) {
                Map<String, Object> ffmpeg = new HashMap<>();
                ffmpeg.put("enabled", item.getFfmpeg().isEnabled());
                ffmpeg.put("resolution", item.getFfmpeg().getResolution());
                ffmpeg.put("bitrate", item.getFfmpeg().getBitrate());
                map.put("ffmpeg", ffmpeg);
            }
            return map;
        }).collect(Collectors.toList());

        List<Map<String, Object>> results = streamService.addStreams(streamMaps);
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    /**
     * 添加流（GET 方式，通过 URL 参数）
     *
     * GET /api/stream/add?name=camera01&rtsp=rtsp://admin:123456@192.168.1.100:554/stream1&rtsp_sub=rtsp://admin:123456@192.168.1.100:554/stream2
     */
    @GetMapping("/add")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> addStreamByGet(
            @RequestParam("name") String name,
            @RequestParam("rtsp") String rtsp,
            @RequestParam(value = "rtsp_sub", required = false) String rtspSub) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("rtsp", rtsp);
        if (rtspSub != null && !rtspSub.isEmpty()) {
            map.put("rtsp_sub", rtspSub);
        }

        List<Map<String, Object>> results = streamService.addStreams(java.util.Collections.singletonList(map));
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    /**
     * 删除流
     *
     * DELETE /api/stream/{streamId}
     */
    @DeleteMapping("/{streamId}")
    public ResponseEntity<ApiResponse<Boolean>> deleteStream(@PathVariable String streamId) {
        boolean result = streamService.deleteStream(streamId);
        return ResponseEntity.ok(ApiResponse.success("删除成功", result));
    }

    /**
     * 获取流状态
     *
     * GET /api/stream/{streamId}
     */
    @GetMapping("/{streamId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStreamStatus(@PathVariable String streamId) {
        Map<String, Object> status = streamService.getStreamStatus(streamId);
        if (status == null) {
            return ResponseEntity.ok(ApiResponse.error("流不存在"));
        }
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    /**
     * 列出所有流
     *
     * GET /api/stream/list
     */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listStreams() {
        List<Map<String, Object>> streams = streamService.listStreams();
        return ResponseEntity.ok(ApiResponse.success(streams));
    }

    /**
     * 切换码流（主码流↔子码流）
     *
     * POST /api/stream/switch/{streamId}
     * 请求体: { "target": "main" | "sub" }
     */
    @PostMapping("/switch/{streamId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> switchStream(
            @PathVariable String streamId,
            @RequestBody Map<String, String> body) {
        String target = body.get("target");
        if (target == null || target.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error("target参数不能为空"));
        }
        try {
            Map<String, Object> result = streamService.switchStream(streamId, target);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 动态开启/关闭转码
     *
     * POST /api/stream/transcode/{streamId}
     * 请求体: { "enabled": true, "resolution": "640x480", "bitrate": "500k" }
     */
    @PostMapping("/transcode/{streamId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> toggleTranscode(
            @PathVariable String streamId,
            @RequestBody TranscodeRequest request) {
        try {
            Map<String, Object> result = streamService.toggleTranscode(
                    streamId, request.isEnabled(), request.getResolution(), request.getBitrate());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }
}
