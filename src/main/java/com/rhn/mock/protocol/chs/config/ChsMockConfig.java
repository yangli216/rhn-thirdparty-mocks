package com.rhn.mock.protocol.chs.config;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 医保运行时可变 Mock 计算与故障注入配置，经 {@code GET/PUT /mock/config} 读写。
 *
 * @param forceDelayMs  对所有交易强制延迟毫秒数，0 表示关闭
 * @param forceInfcode  强制应答的 infcode，null 表示关闭
 */
public record ChsMockConfig(
        BigDecimal ipPoolRatio,
        BigDecimal preselfRatio,
        BigDecimal ipPoolRatioB,
        String xToken,
        boolean signInRequired,
        long forceDelayMs,
        long slowDelayMs,
        Integer forceInfcode,
        String forceErrMsg,
        Map<String, String> itemLevelPrefixes,
        boolean diagnosisRequired) {
}
