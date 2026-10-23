package com.rhn.mock.protocol.fiscal;

import com.rhn.mock.core.spi.MockProtocol;
import com.rhn.mock.protocol.fiscal.config.FiscalMockConfigService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 财政电子票据协议插件：聚合本协议内存态与运行时配置的管理入口。 */
@Component
@Order(5)
public class FiscalProtocol implements MockProtocol {
    public static final String ID = "fiscal";

    private final FiscalMockState state;
    private final FiscalMockConfigService configs;

    public FiscalProtocol(FiscalMockState state, FiscalMockConfigService configs) {
        this.state = state;
        this.configs = configs;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void reset() {
        state.reset();
        configs.reset();
    }

    @Override
    public ObjectNode configView() {
        ObjectNode node = configs.view();
        node.put("receipts", state.receipts().size());
        node.put("callbackDeliveries", state.deliveries().size());
        return node;
    }

    @Override
    public ObjectNode applyConfig(JsonNode patch) {
        configs.update(patch);
        return configView();
    }
}
