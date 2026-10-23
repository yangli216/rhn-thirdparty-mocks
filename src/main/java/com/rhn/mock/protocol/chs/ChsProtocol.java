package com.rhn.mock.protocol.chs;

import com.rhn.mock.core.spi.MockProtocol;
import com.rhn.mock.protocol.chs.config.ChsMockConfigService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 医保协议插件：聚合本协议内存态与运行时配置的管理入口。 */
@Component
@Order(1)
public class ChsProtocol implements MockProtocol {
    public static final String ID = "chs";

    private final ChsMockState state;
    private final ChsMockConfigService configs;

    public ChsProtocol(ChsMockState state, ChsMockConfigService configs) {
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
        return configs.view();
    }

    @Override
    public ObjectNode applyConfig(JsonNode patch) {
        return configs.view(configs.update(patch));
    }
}
