package com.rhn.mock.protocol.chs.config;

import com.rhn.mock.core.JsonNodes;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 持有医保协议运行时 {@link ChsMockConfig}，支持部分字段更新与复位到启动默认。 */
@Component
public class ChsMockConfigService {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ChsMockProperties properties;
    private volatile ChsMockConfig current;

    public ChsMockConfigService(ChsMockProperties properties) {
        this.properties = properties;
        this.current = defaults();
    }

    public ChsMockConfig current() {
        return current;
    }

    /** 复位为启动默认（{@code POST /mock/reset} 调用）。 */
    public void reset() {
        this.current = defaults();
    }

    /** 按补丁中的字段做部分更新，未出现的字段保持原值；返回更新后的配置。 */
    public ChsMockConfig update(JsonNode patch) {
        ChsMockConfig base = current;
        BigDecimal ipPoolRatio = decimal(patch, "ipPoolRatio", base.ipPoolRatio());
        BigDecimal preselfRatio = decimal(patch, "preselfRatio", base.preselfRatio());
        BigDecimal ipPoolRatioB = decimal(patch, "ipPoolRatioB", base.ipPoolRatioB());
        String xToken = patch.has("xToken") ? nullToEmpty(JsonNodes.text(patch.get("xToken"))) : base.xToken();
        boolean signInRequired = patch.has("signInRequired")
                ? Boolean.parseBoolean(JsonNodes.text(patch.get("signInRequired"))) : base.signInRequired();
        long forceDelayMs = patch.has("forceDelayMs") ? longValue(patch, "forceDelayMs", base.forceDelayMs()) : base.forceDelayMs();
        long slowDelayMs = patch.has("slowDelayMs") ? longValue(patch, "slowDelayMs", base.slowDelayMs()) : base.slowDelayMs();
        Integer forceInfcode = patch.has("forceInfcode") ? intValue(patch, "forceInfcode") : base.forceInfcode();
        String forceErrMsg = patch.has("forceErrMsg") ? JsonNodes.text(patch.get("forceErrMsg")) : base.forceErrMsg();
        Map<String, String> levels = patch.has("itemLevelPrefixes") && patch.get("itemLevelPrefixes").isObject()
                ? levels(patch.get("itemLevelPrefixes")) : base.itemLevelPrefixes();
        boolean diagnosisRequired = patch.has("diagnosisRequired")
                ? Boolean.parseBoolean(JsonNodes.text(patch.get("diagnosisRequired"))) : base.diagnosisRequired();
        ChsMockConfig updated = new ChsMockConfig(ipPoolRatio, preselfRatio, ipPoolRatioB, xToken, signInRequired,
                forceDelayMs, slowDelayMs, forceInfcode, forceErrMsg, levels, diagnosisRequired);
        this.current = updated;
        return updated;
    }

    public ObjectNode view() {
        return view(current);
    }

    public ObjectNode view(ChsMockConfig config) {
        ObjectNode node = NODES.objectNode();
        node.put("ipPoolRatio", config.ipPoolRatio().toPlainString());
        node.put("preselfRatio", config.preselfRatio().toPlainString());
        node.put("ipPoolRatioB", config.ipPoolRatioB().toPlainString());
        node.put("xToken", config.xToken());
        node.put("signInRequired", config.signInRequired());
        node.put("diagnosisRequired", config.diagnosisRequired());
        node.put("forceDelayMs", config.forceDelayMs());
        node.put("slowDelayMs", config.slowDelayMs());
        if (config.forceInfcode() == null) {
            node.putNull("forceInfcode");
        } else {
            node.put("forceInfcode", config.forceInfcode());
        }
        if (config.forceErrMsg() == null) {
            node.putNull("forceErrMsg");
        } else {
            node.put("forceErrMsg", config.forceErrMsg());
        }
        ObjectNode prefixes = node.putObject("itemLevelPrefixes");
        config.itemLevelPrefixes().forEach(prefixes::put);
        return node;
    }

    private ChsMockConfig defaults() {
        return new ChsMockConfig(properties.ipPoolRatio(), properties.preselfRatio(), properties.ipPoolRatioB(),
                properties.xToken(), properties.signInRequired(), 0L, properties.slowDelayMs(), null, null,
                Map.of("X", "01", "Z", "02", "T", "03"), properties.diagnosisRequired());
    }

    private static Map<String, String> levels(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            String value = JsonNodes.text(entry.getValue());
            if (value != null) result.put(entry.getKey().toUpperCase(java.util.Locale.ROOT), value);
        });
        return Map.copyOf(result);
    }

    private static BigDecimal decimal(JsonNode patch, String field, BigDecimal fallback) {
        if (!patch.has(field)) return fallback;
        BigDecimal value = JsonNodes.decimal(patch.get(field));
        return value == null ? fallback : value;
    }

    private static long longValue(JsonNode patch, String field, long fallback) {
        String value = JsonNodes.text(patch.get(field));
        if (value == null) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static Integer intValue(JsonNode patch, String field) {
        JsonNode node = patch.get(field);
        if (node == null || node.isNull()) return null;
        String value = JsonNodes.text(node);
        if (value == null) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
