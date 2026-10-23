package com.rhn.mock.protocol.ca.config;

/**
 * 运行时可改配置（{@code PUT /mock/config?protocol=ca}）。
 *
 * @param fault          全局基础设施故障：NONE / HTTP500 / BADJSON / SLOW / TIMEOUT
 * @param faultOp        故障只作用于该操作（如 {@code signing/submit}），空表示全部业务操作
 * @param forceErrorCode 非空则所有业务操作直接以该错误码失败
 */
public record CaMockConfig(String clientSecret, long forceDelayMs, long slowDelayMs, long timeoutDelayMs,
                           String fault, String faultOp, String forceErrorCode, String forceMessage,
                           int loginChallengeTtlSeconds, boolean autoConfirmOrders, String autoConfirmLoginSigner) {
}
