package com.mediagateway.model;

import lombok.Data;

/**
 * 转码请求
 */
@Data
public class TranscodeRequest {
    /** 是否启用转码 */
    private boolean enabled;
    /** 目标分辨率，如640x480 */
    private String resolution;
    /** 目标码率，如500k */
    private String bitrate;
}
