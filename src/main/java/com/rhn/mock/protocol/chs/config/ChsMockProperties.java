package com.rhn.mock.protocol.chs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 医保 Mock 协议启动配置（{@code mock.chs.*}）。端口与监听地址由 {@code server.*} 控制；
 * 运行时可变部分见 {@link ChsMockConfig}，经 {@code /mock/config} 读写。
 */
@ConfigurationProperties(prefix = "mock.chs")
public record ChsMockProperties(
        @DefaultValue("/hsa/api") String businessPath,
        @DefaultValue("mock-token") String xToken,
        @DefaultValue("true") boolean signInRequired,
        @DefaultValue("0.80") BigDecimal ipPoolRatio,
        @DefaultValue("0.15") BigDecimal preselfRatio,
        @DefaultValue("0.70") BigDecimal ipPoolRatioB,
        @DefaultValue("31000") long slowDelayMs,
        @DefaultValue("false") boolean diagnosisRequired,
        @DefaultValue Map<String, String> certAliases) {
}
