package com.rhn.mock.core.fault;

import com.rhn.mock.core.JsonNodes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Set;

/**
 * 支付协议（alipay / wxpay）共用的运行时故障与中间状态配置，经 {@code /mock/config?protocol=<id>} 读写。
 *
 * <p>全局故障：{@code fault} 作用于匹配 {@code faultOp} 的请求（空=全部），{@code faultCount>0} 表示只对接下来 N 次生效
 * （用完自动恢复 NONE），0 表示一直生效。同一故障也可由单号后缀逐单触发（见 {@link PaymentScenarios#fault}）。
 * {@code faultProcessed=true}（默认）表示故障发生在应答阶段：业务已处理、只是应答丢失/损坏，便于演练“超时后查单恢复”；
 * false 表示请求根本没被处理（HTTP500 / BADBODY / TIMEOUT 不落订单）。
 * {@code pendingQueries} 是“处理中”订单被查询多少次后自动落到终态。
 */
public final class PaymentFaultConfig {
    public static final Set<String> FAULTS = Set.of("NONE", "HTTP500", "BADBODY", "SLOW", "TIMEOUT", "BADSIGN", "NOSIGN");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final long defaultSlowMs;
    private final long defaultTimeoutMs;
    private final int defaultPendingQueries;
    private String fault = "NONE";
    private String faultOp = "";
    private int faultCount;
    private boolean faultProcessed = true;
    private long slowDelayMs;
    private long timeoutDelayMs;
    private int pendingQueries;

    public PaymentFaultConfig(long slowDelayMs, long timeoutDelayMs, int pendingQueries) {
        this.defaultSlowMs = slowDelayMs;
        this.defaultTimeoutMs = timeoutDelayMs;
        this.defaultPendingQueries = pendingQueries;
        reset();
    }

    public synchronized void reset() {
        fault = "NONE";
        faultOp = "";
        faultCount = 0;
        faultProcessed = true;
        slowDelayMs = defaultSlowMs;
        timeoutDelayMs = defaultTimeoutMs;
        pendingQueries = defaultPendingQueries;
    }

    /**
     * 取本次请求应施加的故障（逐单后缀优先于全局），没有返回 null。{@code op} 是短操作名
     * （pay / query / refund / refundquery / bill），全局故障消耗一次 {@code faultCount}。
     */
    public synchronized String take(String op, String reference) {
        String perOrder = PaymentScenarios.fault(reference);
        if (perOrder != null) return perOrder;
        if ("NONE".equals(fault) || !(faultOp.isBlank() || faultOp.equalsIgnoreCase(op))) return null;
        String active = fault;
        if (faultCount > 0 && --faultCount == 0) fault = "NONE";
        return active;
    }

    public synchronized boolean faultProcessed() {
        return faultProcessed;
    }

    public synchronized long slowDelayMs() {
        return slowDelayMs;
    }

    public synchronized long timeoutDelayMs() {
        return timeoutDelayMs;
    }

    public synchronized int pendingQueries() {
        return pendingQueries;
    }

    public synchronized void update(JsonNode patch) {
        if (patch.has("fault")) {
            String value = JsonNodes.text(patch.get("fault"));
            String normalized = value == null ? "NONE" : value.toUpperCase(Locale.ROOT);
            if ("BADJSON".equals(normalized) || "BADXML".equals(normalized)) normalized = "BADBODY";
            if (!FAULTS.contains(normalized)) throw new IllegalArgumentException("fault 只能是 " + FAULTS);
            fault = normalized;
        }
        if (patch.has("faultOp")) {
            String value = JsonNodes.text(patch.get("faultOp"));
            faultOp = value == null ? "" : value;
        }
        faultCount = (int) number(patch, "faultCount", faultCount);
        if (patch.has("faultProcessed")) {
            faultProcessed = Boolean.parseBoolean(JsonNodes.text(patch.get("faultProcessed")));
        }
        slowDelayMs = number(patch, "slowDelayMs", slowDelayMs);
        timeoutDelayMs = number(patch, "timeoutDelayMs", timeoutDelayMs);
        pendingQueries = (int) number(patch, "pendingQueries", pendingQueries);
    }

    public synchronized void view(ObjectNode node) {
        node.put("fault", fault);
        node.put("faultOp", faultOp);
        node.put("faultCount", faultCount);
        node.put("faultProcessed", faultProcessed);
        node.put("slowDelayMs", slowDelayMs);
        node.put("timeoutDelayMs", timeoutDelayMs);
        node.put("pendingQueries", pendingQueries);
    }

    public ObjectNode view() {
        ObjectNode node = NODES.objectNode();
        view(node);
        return node;
    }

    private static long number(JsonNode patch, String field, long fallback) {
        if (!patch.has(field)) return fallback;
        String value = JsonNodes.text(patch.get(field));
        if (value == null) return fallback;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw new IllegalArgumentException(field + " 不能为负");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " 必须是整数");
        }
    }
}
