package com.rhn.mock.protocol.ca.config;

import com.rhn.mock.core.JsonNodes;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Set;

/** 持有 CA 协议运行时 {@link CaMockConfig}，支持部分字段更新与复位到启动默认。 */
@Component
public class CaMockConfigService {
    public static final Set<String> FAULTS = Set.of("NONE", "HTTP500", "BADJSON", "SLOW", "TIMEOUT");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final CaMockProperties properties;
    private volatile CaMockConfig current;

    public CaMockConfigService(CaMockProperties properties) {
        this.properties = properties;
        this.current = defaults();
    }

    public CaMockConfig current() {
        return current;
    }

    public void reset() {
        this.current = defaults();
    }

    /** 按补丁中的字段做部分更新，未出现的字段保持原值。 */
    public CaMockConfig update(JsonNode patch) {
        CaMockConfig base = current;
        String fault = base.fault();
        if (patch.has("fault")) {
            String value = JsonNodes.text(patch.get("fault"));
            fault = value == null ? "NONE" : value.toUpperCase(Locale.ROOT);
            if (!FAULTS.contains(fault)) throw new IllegalArgumentException("fault 只能是 " + FAULTS);
        }
        CaMockConfig updated = new CaMockConfig(
                patch.has("clientSecret") ? orEmpty(JsonNodes.text(patch.get("clientSecret"))) : base.clientSecret(),
                longValue(patch, "forceDelayMs", base.forceDelayMs()),
                longValue(patch, "slowDelayMs", base.slowDelayMs()),
                longValue(patch, "timeoutDelayMs", base.timeoutDelayMs()),
                fault,
                patch.has("faultOp") ? orEmpty(JsonNodes.text(patch.get("faultOp"))) : base.faultOp(),
                patch.has("forceErrorCode") ? JsonNodes.text(patch.get("forceErrorCode")) : base.forceErrorCode(),
                patch.has("forceMessage") ? JsonNodes.text(patch.get("forceMessage")) : base.forceMessage(),
                (int) longValue(patch, "loginChallengeTtlSeconds", base.loginChallengeTtlSeconds()),
                patch.has("autoConfirmOrders") ? Boolean.parseBoolean(JsonNodes.text(patch.get("autoConfirmOrders")))
                        : base.autoConfirmOrders(),
                patch.has("autoConfirmLoginSigner") ? orEmpty(JsonNodes.text(patch.get("autoConfirmLoginSigner")))
                        : base.autoConfirmLoginSigner());
        this.current = updated;
        return updated;
    }

    public ObjectNode view() {
        CaMockConfig config = current;
        ObjectNode node = NODES.objectNode();
        node.put("basePath", properties.basePath());
        node.put("vendorCode", properties.vendorCode());
        node.put("clientId", properties.clientId());
        node.put("clientSecret", config.clientSecret());
        node.put("forceDelayMs", config.forceDelayMs());
        node.put("slowDelayMs", config.slowDelayMs());
        node.put("timeoutDelayMs", config.timeoutDelayMs());
        node.put("fault", config.fault());
        node.put("faultOp", config.faultOp());
        if (config.forceErrorCode() == null) node.putNull("forceErrorCode");
        else node.put("forceErrorCode", config.forceErrorCode());
        if (config.forceMessage() == null) node.putNull("forceMessage");
        else node.put("forceMessage", config.forceMessage());
        node.put("loginChallengeTtlSeconds", config.loginChallengeTtlSeconds());
        node.put("autoConfirmOrders", config.autoConfirmOrders());
        node.put("autoConfirmLoginSigner", config.autoConfirmLoginSigner());
        return node;
    }

    private CaMockConfig defaults() {
        return new CaMockConfig(properties.clientSecret(), 0L, properties.slowDelayMs(), properties.timeoutDelayMs(),
                "NONE", "", null, null, properties.loginChallengeTtlSeconds(), properties.autoConfirmOrders(),
                properties.autoConfirmLoginSigner());
    }

    private static long longValue(JsonNode patch, String field, long fallback) {
        if (!patch.has(field)) return fallback;
        String value = JsonNodes.text(patch.get(field));
        if (value == null) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
