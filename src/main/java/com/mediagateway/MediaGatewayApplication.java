package com.mediagateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MediaGateway 启动类
 *
 * 视频流网关服务 - 管理RTSP摄像头流，调用MediaMTX，返回播放地址
 */
@SpringBootApplication
public class MediaGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaGatewayApplication.class, args);
    }
}
