package com.mediagateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 流状态
 */
@Data
public class StreamStatus {
    private String streamId;
    private String name;
    private String type;
    private String rtsp;
    @JsonProperty("rtsp_sub")
    private String rtspSub;
    private String hls;
    private String webrtc;
    @JsonProperty("http_flv")
    private String httpFlv;
    private boolean transcoding;
    private AdaptiveStatus adaptive;

    @Data
    public static class AdaptiveStatus {
        private int failCount;
        private int successCount;
    }
}
