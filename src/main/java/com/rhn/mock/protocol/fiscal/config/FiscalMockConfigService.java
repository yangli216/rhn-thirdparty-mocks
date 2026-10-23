package com.rhn.mock.protocol.fiscal.config;

import com.rhn.mock.core.JsonNodes;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Set;

/** 持有财政票据协议运行时 {@link FiscalMockConfig}，支持部分字段更新与复位到启动默认。 */
@Component
public class FiscalMockConfigService {
    public static final Set<String> FAULTS = Set.of("NONE", "HTTP500", "BADJSON", "SLOW", "TIMEOUT");
    public static final Set<String> SCENARIOS = Set.of("NORMAL", "PENDING", "FAIL");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final FiscalMockProperties properties;
    private volatile FiscalMockConfig current;

    public FiscalMockConfigService(FiscalMockProperties properties) {
        this.properties = properties;
        this.current = defaults();
    }

    public FiscalMockConfig current() {
        return current;
    }

    public void reset() {
        this.current = defaults();
    }

    public FiscalMockConfig update(JsonNode patch) {
        FiscalMockConfig base = current;
        String fault = base.fault();
        if (patch.has("fault")) {
            String value = JsonNodes.text(patch.get("fault"));
            fault = value == null ? "NONE" : value.toUpperCase(Locale.ROOT);
            if (!FAULTS.contains(fault)) throw new IllegalArgumentException("fault 只能是 " + FAULTS);
        }
        String scenario = base.issueScenario();
        if (patch.has("issueScenario")) {
            String value = JsonNodes.text(patch.get("issueScenario"));
            scenario = value == null ? "NORMAL" : value.toUpperCase(Locale.ROOT);
            if (!SCENARIOS.contains(scenario)) throw new IllegalArgumentException("issueScenario 只能是 " + SCENARIOS);
        }
        FiscalMockConfig updated = new FiscalMockConfig(
                patch.has("clientSecret") ? orEmpty(JsonNodes.text(patch.get("clientSecret"))) : base.clientSecret(),
                longValue(patch, "forceDelayMs", base.forceDelayMs()),
                longValue(patch, "slowDelayMs", base.slowDelayMs()),
                longValue(patch, "timeoutDelayMs", base.timeoutDelayMs()),
                fault,
                patch.has("faultOp") ? orEmpty(JsonNodes.text(patch.get("faultOp"))) : base.faultOp(),
                scenario,
                (int) longValue(patch, "pendingQueries", base.pendingQueries()),
                patch.has("forceErrorCode") ? JsonNodes.text(patch.get("forceErrorCode")) : base.forceErrorCode(),
                patch.has("forceMessage") ? JsonNodes.text(patch.get("forceMessage")) : base.forceMessage(),
                patch.has("callbackUrl") ? orEmpty(JsonNodes.text(patch.get("callbackUrl"))) : base.callbackUrl(),
                patch.has("callbackSecret") ? orEmpty(JsonNodes.text(patch.get("callbackSecret"))) : base.callbackSecret(),
                patch.has("autoCallback") ? Boolean.parseBoolean(JsonNodes.text(patch.get("autoCallback"))) : base.autoCallback(),
                longValue(patch, "callbackDelayMs", base.callbackDelayMs()),
                patch.has("verifyBaseUrl") ? orEmpty(JsonNodes.text(patch.get("verifyBaseUrl"))) : base.verifyBaseUrl());
        this.current = updated;
        return updated;
    }

    public ObjectNode view() {
        FiscalMockConfig config = current;
        ObjectNode node = NODES.objectNode();
        node.put("clientSecret", config.clientSecret());
        node.put("forceDelayMs", config.forceDelayMs());
        node.put("slowDelayMs", config.slowDelayMs());
        node.put("timeoutDelayMs", config.timeoutDelayMs());
        node.put("fault", config.fault());
        node.put("faultOp", config.faultOp());
        node.put("issueScenario", config.issueScenario());
        node.put("pendingQueries", config.pendingQueries());
        node.put("forceErrorCode", config.forceErrorCode());
        node.put("forceMessage", config.forceMessage());
        node.put("callbackUrl", config.callbackUrl());
        node.put("callbackSecret", config.callbackSecret());
        node.put("autoCallback", config.autoCallback());
        node.put("callbackDelayMs", config.callbackDelayMs());
        node.put("verifyBaseUrl", config.verifyBaseUrl());
        return node;
    }

    private FiscalMockConfig defaults() {
        return new FiscalMockConfig(properties.clientSecret(), 0L, properties.slowDelayMs(), properties.timeoutDelayMs(),
                "NONE", "", "NORMAL", properties.pendingQueries(), null, null, properties.callbackUrl(),
                properties.callbackSecret(), properties.autoCallback(), properties.callbackDelayMs(),
                properties.verifyBaseUrl());
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
