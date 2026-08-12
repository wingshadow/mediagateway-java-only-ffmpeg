package com.mediagateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 流项
 */
@Data
public class StreamItem {
    /** 流名称 */
    @NotBlank(message = "流名称不能为空")
    private String name;

    /** 主码流RTSP地址 */
    @NotBlank(message = "RTSP地址不能为空")
    private String rtsp;

    /** 子码流RTSP地址 */
    @JsonProperty("rtsp_sub")
    private String rtspSub;

    /** FFmpeg转码参数，为空时不启用转码 */
    private FFmpegParams ffmpeg;
}
