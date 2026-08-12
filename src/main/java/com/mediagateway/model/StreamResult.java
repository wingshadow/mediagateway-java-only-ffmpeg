package com.mediagateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 流结果
 */
@Data
public class StreamResult {
    private String streamId;
    private String name;
    private String rtsp;
    @JsonProperty("rtsp_sub")
    private String rtspSub;
    private String hls;
    private String webrtc;
    @JsonProperty("http_flv")
    private String httpFlv;
    private boolean transcoding;
}
