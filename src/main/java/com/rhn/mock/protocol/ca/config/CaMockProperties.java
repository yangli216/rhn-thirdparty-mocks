package com.rhn.mock.protocol.ca.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * CA 厂商 Mock 协议启动配置（{@code mock.ca.*}）。
 *
 * <p>根证书与时间戳密钥指向**联调专用的一次性 throwaway 材料**（非真实 CA）：固定随包发布，
 * 使 RHN 侧配置一次信任锚后重启 Mock 不必重配；终端证书则每次由根 CA 现签。
 * {@code client-id}/{@code client-secret} 是 RHN 适配器调用 Mock 时携带的凭据，对应设计稿里
 * 厂商连接参数 {@code clientId/clientSecret}，{@code client-secret} 置空即关闭校验。
 */
@ConfigurationProperties(prefix = "mock.ca")
public record CaMockProperties(
        @DefaultValue("/ca") String basePath,
        @DefaultValue("mock-ca") String vendorCode,
        @DefaultValue("mock-ca-client") String clientId,
        @DefaultValue("mock-ca-secret") String clientSecret,
        @DefaultValue("classpath:mock/ca/ca-root-cert.pem") String rootCert,
        @DefaultValue("classpath:mock/ca/ca-root-key.pem") String rootKey,
        @DefaultValue("classpath:mock/ca/ca-tsa-cert.pem") String tsaCert,
        @DefaultValue("classpath:mock/ca/ca-tsa-key.pem") String tsaKey,
        @DefaultValue("365") int signerValidityDays,
        @DefaultValue("120") int loginChallengeTtlSeconds,
        @DefaultValue("5000") long slowDelayMs,
        @DefaultValue("31000") long timeoutDelayMs,
        @DefaultValue("false") boolean autoConfirmOrders,
        @DefaultValue("") String autoConfirmLoginSigner) {
}
