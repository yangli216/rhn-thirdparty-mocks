package com.rhn.mock.protocol.alipay;

import com.rhn.mock.core.JsonNodes;
import com.rhn.mock.core.spi.MockProtocol;
import com.rhn.mock.protocol.alipay.config.AlipayMockProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** 支付宝协议插件：聚合本协议内存态与运行时开关的管理入口。 */
@Component
@Order(2)
public class AlipayProtocol implements MockProtocol {
    public static final String ID = "alipay";
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final AlipayMockProperties properties;
    private final AlipayMockState state;

    public AlipayProtocol(AlipayMockProperties properties, AlipayMockState state) {
        this.properties = properties;
        this.state = state;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void reset() {
        state.reset();
    }

    @Override
    public ObjectNode configView() {
        ObjectNode node = NODES.objectNode();
        node.put("gatewayPath", properties.gatewayPath());
        node.put("appId", properties.appId());
        node.put("sellerId", properties.sellerId());
        node.put("signType", properties.signType());
        node.put("charset", properties.charset());
        node.put("requireRequestSign", state.requestSignRequired());
        node.put("transactions", state.transactions());
        node.put("refunds", state.refunds());
        state.faults().view(node);
        return node;
    }

    @Override
    public ObjectNode applyConfig(JsonNode patch) {
        if (patch != null && patch.has("requireRequestSign") && !patch.get("requireRequestSign").isNull()) {
            String value = JsonNodes.text(patch.get("requireRequestSign"));
            if (value != null) state.requestSignRequired(Boolean.parseBoolean(value));
        }
        if (patch != null) state.faults().update(patch);
        return configView();
    }
}
