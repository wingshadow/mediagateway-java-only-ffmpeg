package com.mediagateway.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.Collections;

/**
 * Web 配置：HLS 静态资源映射、跨域支持
 *
 * 跨域使用 CorsFilter（Servlet Filter 级别）而非 WebMvcConfigurer.addCorsMappings()，
 * 因为后者对 StreamingResponseBody 流式响应支持不好，CORS 头可能不会正确添加。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final GatewayProperties gatewayProperties;

    public WebConfig(GatewayProperties gatewayProperties) {
        this.gatewayProperties = gatewayProperties;
    }

    /**
     * 将本地 hls 目录映射为 HTTP 静态资源路径
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String urlPrefix = gatewayProperties.getHls().getUrlPrefix();
        String outputDir = gatewayProperties.getHls().getOutputDir();
        registry.addResourceHandler(urlPrefix + "/**")
                .addResourceLocations("file:" + outputDir + "/");
    }

    /**
     * 使用 CorsFilter 在 Servlet Filter 层统一处理跨域，
     * 确保对所有路径（包括 /api/**、/flv/**、/hls/** 及 StreamingResponseBody）都生效。
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Collections.singletonList("*"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "DELETE", "HEAD", "OPTIONS"));
        config.setAllowedHeaders(Collections.singletonList("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}