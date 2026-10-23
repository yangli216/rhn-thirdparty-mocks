package com.rhn.mock.protocol.wxpay;

import com.rhn.mock.core.JsonNodes;
import com.rhn.mock.core.spi.MockProtocol;
import com.rhn.mock.protocol.wxpay.config.WxpayMockProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** 微信支付协议插件：聚合本协议内存态与运行时开关的管理入口。 */
@Component
@Order(3)
public class WxpayProtocol implements MockProtocol {
    public static final String ID = "wxpay";
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final WxpayMockProperties properties;
    private final WxpayMockState state;

    public WxpayProtocol(WxpayMockProperties properties, WxpayMockState state) {
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
        node.put("basePath", properties.basePath());
        node.put("appId", properties.appId());
        node.put("mchId", properties.mchId());
        node.put("defaultBankType", properties.defaultBankType());
        node.put("requireRequestSign", state.requestSignRequired());
        node.put("transactions", state.transactions());
        node.put("refunds", state.refunds());
        node.put("reportRefundState", state.reportRefundState());
        state.faults().view(node);
        return node;
    }

    @Override
    public ObjectNode applyConfig(JsonNode patch) {
        if (patch != null && patch.has("requireRequestSign") && !patch.get("requireRequestSign").isNull()) {
            String value = JsonNodes.text(patch.get("requireRequestSign"));
            if (value != null) state.requestSignRequired(Boolean.parseBoolean(value));
        }
        if (patch != null && patch.has("reportRefundState") && !patch.get("reportRefundState").isNull()) {
            state.reportRefundState(Boolean.parseBoolean(JsonNodes.text(patch.get("reportRefundState"))));
        }
        if (patch != null) state.faults().update(patch);
        return configView();
    }
}
