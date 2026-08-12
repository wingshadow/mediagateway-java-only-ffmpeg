package com.mediagateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * FFmpeg转码参数
 */
@Data
public class FFmpegParams {
    /** 是否启用转码 */
    private boolean enabled = false;
    /** 目标分辨率，如640x480 */
    private String resolution;
    /** 目标码率，如500k */
    private String bitrate;
}
