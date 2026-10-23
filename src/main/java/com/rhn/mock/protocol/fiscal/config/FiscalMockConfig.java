package com.rhn.mock.protocol.fiscal.config;

/**
 * 财政票据运行时可变配置，经 {@code GET/PUT /mock/config?protocol=fiscal} 读写。
 *
 * @param fault          全局 HTTP 层故障：NONE / HTTP500 / BADJSON / SLOW / TIMEOUT
 * @param faultOp        故障只作用于该操作（issue/query/void/redFlush），空表示全部
 * @param issueScenario  开具默认场景：NORMAL / PENDING / FAIL（payerName 的 TEST-* 优先）
 * @param pendingQueries PENDING 场景下，开具后要被查询多少次才出票成功
 */
public record FiscalMockConfig(
        String clientSecret,
        long forceDelayMs,
        long slowDelayMs,
        long timeoutDelayMs,
        String fault,
        String faultOp,
        String issueScenario,
        int pendingQueries,
        String forceErrorCode,
        String forceMessage,
        String callbackUrl,
        String callbackSecret,
        boolean autoCallback,
        long callbackDelayMs,
        String verifyBaseUrl) {
}
