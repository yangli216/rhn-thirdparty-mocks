package com.rhn.mock.core;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

/** JsonNode 只读访问小工具：区分"字段缺失/null"与"空串"，并容忍金额用数字或字符串表示。 */
public final class JsonNodes {
    private JsonNodes() {
    }

    /** 字段存在且不是 JSON null。 */
    public static boolean present(JsonNode node, String field) {
        if (node == null) return false;
        JsonNode value = node.get(field);
        return value != null && !value.isNull();
    }

    /** 取标量文本，缺失/null/空串/容器均返回 null。 */
    public static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isObject() || node.isArray()) return null;
        String value = node.asString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static String text(JsonNode parent, String field) {
        return parent == null ? null : text(parent.get(field));
    }

    /** 取金额，数字与字符串均可；不可解析返回 null。 */
    public static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isObject() || node.isArray()) return null;
        String value = text(node);
        if (value == null) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public static BigDecimal decimal(JsonNode parent, String field) {
        return parent == null ? null : decimal(parent.get(field));
    }
}
