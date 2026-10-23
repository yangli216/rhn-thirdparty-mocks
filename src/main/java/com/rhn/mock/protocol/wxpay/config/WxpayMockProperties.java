package com.rhn.mock.protocol.wxpay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 微信支付 API v2 Mock 协议启动配置（{@code mock.wxpay.*}）。
 *
 * <p>{@code app-id}/{@code mch-id} 仅作请求缺省回填；响应里的 {@code appid}/{@code mch_id}
 * 优先回显请求值，使同一实例可服务任意商户配置。{@code api-key} 用于 MD5 加签/验签，
 * 必须与调用方商户配置的 API v2 密钥一致——这是唯一的共享密钥约定。
 */
@ConfigurationProperties(prefix = "mock.wxpay")
public record WxpayMockProperties(
        @DefaultValue("/wxpay") String basePath,
        @DefaultValue("wxmockappid00001") String appId,
        @DefaultValue("1900000109") String mchId,
        @DefaultValue("0123456789abcdef0123456789abcdef") String apiKey,
        @DefaultValue("CFB_DEBIT") String defaultBankType,
        @DefaultValue("true") boolean requireRequestSign,
        @DefaultValue("3000") long slowDelayMs,
        @DefaultValue("20000") long timeoutDelayMs,
        @DefaultValue("2") int pendingQueries) {
}
