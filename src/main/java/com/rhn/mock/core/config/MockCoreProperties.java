package com.rhn.mock.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Mock 服务通用启动配置（{@code mock.*}）。各协议自身的启动配置在 {@code mock.<protocol>.*}。
 * 端口与监听地址由 {@code server.*} 控制。
 */
@ConfigurationProperties(prefix = "mock")
public record MockCoreProperties(@DefaultValue("1000") int requestsBufferSize) {
}
