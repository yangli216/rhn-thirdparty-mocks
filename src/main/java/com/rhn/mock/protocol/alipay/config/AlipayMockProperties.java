package com.rhn.mock.protocol.alipay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 支付宝 Mock 协议启动配置（{@code mock.alipay.*}）。
 *
 * <p>两个密钥路径均指向**联调专用的一次性 throwaway 材料**（非真实商户密钥）：
 * {@code response-sign-private-key} 用于给响应加签，{@code request-verify-public-key} 用于校验请求。
 * 请求是否验签可在运行时通过 {@code /mock/config?protocol=alipay} 的 {@code requireRequestSign} 临时关闭排障。
 */
@ConfigurationProperties(prefix = "mock.alipay")
public record AlipayMockProperties(
        @DefaultValue("/alipay/gateway.do") String gatewayPath,
        @DefaultValue("mock-alipay-app") String appId,
        @DefaultValue("2088mock") String sellerId,
        @DefaultValue("RSA2") String signType,
        @DefaultValue("UTF-8") String charset,
        @DefaultValue("true") boolean requireRequestSign,
        @DefaultValue("classpath:mock/alipay/alipay-private-key.pem") String responseSignPrivateKey,
        @DefaultValue("classpath:mock/alipay/app-public-key.pem") String requestVerifyPublicKey,
        @DefaultValue("http://127.0.0.1:18090/alipay/bill/download") String billDownloadBaseUrl,
        @DefaultValue("3000") long slowDelayMs,
        @DefaultValue("20000") long timeoutDelayMs,
        @DefaultValue("2") int pendingQueries) {
}
