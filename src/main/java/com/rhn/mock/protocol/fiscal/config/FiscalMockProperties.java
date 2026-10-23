package com.rhn.mock.protocol.fiscal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 财政电子票据 Mock 协议启动配置（{@code mock.fiscal.*}）。协议为自拟 HTTP/JSON（RHN 尚无 HTTP 客户端），见 docs/fiscal-mock-protocol.md。
 * {@code client-secret} 置空关闭 X-Client-* 校验；{@code callback-url} 非空时异步结果会主动推送到该地址。
 */
@ConfigurationProperties(prefix = "mock.fiscal")
public record FiscalMockProperties(
        @DefaultValue("/fiscal") String basePath,
        @DefaultValue("mock-fiscal-client") String clientId,
        @DefaultValue("mock-fiscal-secret") String clientSecret,
        @DefaultValue("http://127.0.0.1:18090/fiscal/verify") String verifyBaseUrl,
        @DefaultValue("5000") long slowDelayMs,
        @DefaultValue("31000") long timeoutDelayMs,
        @DefaultValue("1") int pendingQueries,
        @DefaultValue("") String callbackUrl,
        @DefaultValue("mock-fiscal-callback-secret") String callbackSecret,
        @DefaultValue("false") boolean autoCallback,
        @DefaultValue("1000") long callbackDelayMs) {
}
