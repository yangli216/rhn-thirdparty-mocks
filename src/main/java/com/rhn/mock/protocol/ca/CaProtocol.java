package com.rhn.mock.protocol.ca;

import com.rhn.mock.core.spi.MockProtocol;
import com.rhn.mock.protocol.ca.config.CaMockConfigService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** CA 协议插件：聚合本协议内存态与运行时配置的管理入口。 */
@Component
@Order(4)
public class CaProtocol implements MockProtocol {
    public static final String ID = "ca";

    private final CaMockState state;
    private final CaMockConfigService configs;
    private final CaAuthority authority;

    public CaProtocol(CaMockState state, CaMockConfigService configs, CaAuthority authority) {
        this.state = state;
        this.configs = configs;
        this.authority = authority;
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
        node.put("signers", state.signers().size());
        node.put("orders", state.orders().size());
        node.put("rootSubject", authority.rootDn());
        node.put("rootFingerprint", CaCrypto.fingerprint(authority.root()));
        return node;
    }

    @Override
    public ObjectNode applyConfig(JsonNode patch) {
        configs.update(patch);
        return configView();
    }
}
