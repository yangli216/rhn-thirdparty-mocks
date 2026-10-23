package com.rhn.mock;

import com.rhn.mock.core.config.MockCoreProperties;
import com.rhn.mock.protocol.alipay.config.AlipayMockProperties;
import com.rhn.mock.protocol.ca.config.CaMockProperties;
import com.rhn.mock.protocol.chs.config.ChsMockProperties;
import com.rhn.mock.protocol.fiscal.config.FiscalMockProperties;
import com.rhn.mock.protocol.wxpay.config.WxpayMockProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 三方 Mock 服务启动类：单进程、内存态、重启即清空。
 * 各协议按路径分区：医保 {@code /hsa/api}、支付宝 {@code /alipay/gateway.do}、微信 v2 {@code /wxpay/**}、CA 厂商 {@code /ca/v1/**}（测试辅助 {@code /mock/ca/**}）、财政电子票据 {@code /fiscal/v1/**}（测试辅助 {@code /mock/fiscal/**}）。
 */
@SpringBootApplication
@EnableConfigurationProperties({MockCoreProperties.class, ChsMockProperties.class, AlipayMockProperties.class,
        WxpayMockProperties.class, CaMockProperties.class, FiscalMockProperties.class})
public class MockApplication {
    public static void main(String[] args) {
        SpringApplication.run(MockApplication.class, args);
    }
}
