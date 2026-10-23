package com.rhn.mock.core.bill;

import com.rhn.mock.core.JsonNodes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 渠道账单的“可构造差异”：在 Mock 已有的真实交易之上，按需让账单与 RHN 本地账不一致，用来演练对账差异处理。
 *
 * <ul>
 *   <li>{@code omit} / {@code omitRefund}：账单漏掉某笔支付 / 退款 → RHN 本地单边；</li>
 *   <li>{@code extra}：账单多出 RHN 没有的支付 / 退款 → 渠道单边（给已有支付补一条退款即“状态差异”）；</li>
 *   <li>{@code amount}：覆盖某笔金额 → 金额差异；</li>
 *   <li>{@code refundStatus}：覆盖退款状态（微信 PROCESSING / FAIL / CHANGE）→ 状态差异；</li>
 *   <li>{@code duplicate}：同一笔在账单里出现两次 → 重复交易；</li>
 *   <li>{@code fetch}：下载阶段结果（NOT_READY / NOT_FOUND / NO_DATA / AUTH_FAILED / RATE_LIMITED / UNSUPPORTED），
 *       或 CORRUPT（汇总故意与明细对不上）。</li>
 * </ul>
 */
public class BillAnomalies {
    public static final Set<String> FETCH_MODES = Set.of("AVAILABLE", "NOT_READY", "NOT_FOUND", "NO_DATA",
            "AUTH_FAILED", "RATE_LIMITED", "UNSUPPORTED", "CORRUPT");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private String fetch = "AVAILABLE";
    private final Set<String> omit = new LinkedHashSet<>();
    private final Set<String> omitRefund = new LinkedHashSet<>();
    private final Set<String> duplicate = new LinkedHashSet<>();
    private final Map<String, String> amount = new LinkedHashMap<>();
    private final Map<String, String> refundStatus = new LinkedHashMap<>();
    private final List<BillRow> extra = new ArrayList<>();

    public synchronized String fetch() {
        return fetch;
    }

    public synchronized void clear() {
        fetch = "AVAILABLE";
        omit.clear();
        omitRefund.clear();
        duplicate.clear();
        amount.clear();
        refundStatus.clear();
        extra.clear();
    }

    /** 部分更新：补丁里出现的字段整体替换，未出现的保持。 */
    public synchronized void update(JsonNode patch) {
        if (patch.has("fetch")) {
            String value = JsonNodes.text(patch.get("fetch"));
            String mode = value == null ? "AVAILABLE" : value.toUpperCase(Locale.ROOT);
            if (!FETCH_MODES.contains(mode)) throw new IllegalArgumentException("fetch 只能是 " + FETCH_MODES);
            fetch = mode;
        }
        replace(omit, patch, "omit");
        replace(omitRefund, patch, "omitRefund");
        replace(duplicate, patch, "duplicate");
        replace(amount, patch, "amount");
        replace(refundStatus, patch, "refundStatus");
        if (patch.has("extra")) {
            extra.clear();
            JsonNode rows = patch.get("extra");
            if (rows.isArray()) for (JsonNode row : rows) extra.add(extraRow(row));
        }
    }

    public synchronized ObjectNode view() {
        ObjectNode node = NODES.objectNode();
        node.put("fetch", fetch);
        ArrayNode omitted = node.putArray("omit");
        omit.forEach(omitted::add);
        ArrayNode omittedRefunds = node.putArray("omitRefund");
        omitRefund.forEach(omittedRefunds::add);
        ArrayNode duplicates = node.putArray("duplicate");
        duplicate.forEach(duplicates::add);
        ObjectNode amounts = node.putObject("amount");
        amount.forEach(amounts::put);
        ObjectNode statuses = node.putObject("refundStatus");
        refundStatus.forEach(statuses::put);
        ArrayNode extras = node.putArray("extra");
        for (BillRow row : extra) {
            ObjectNode item = extras.addObject();
            item.put("type", row.type());
            item.put("outTradeNo", row.outTradeNo());
            item.put("tradeNo", row.tradeNo());
            item.put("outRefundNo", row.outRefundNo());
            item.put("refundId", row.refundId());
            item.put("amount", row.amount().toPlainString());
            item.put("time", row.time().format(TIME));
            item.put("refundStatus", row.refundStatus());
        }
        return node;
    }

    /** 以指定账单日的真实交易 {@code base} 为底，按差异配置生成最终账单行（含账单日内的 extra）。 */
    public synchronized List<BillRow> apply(List<BillRow> base, LocalDate date) {
        List<BillRow> rows = new ArrayList<>();
        for (BillRow row : base) {
            if (row.refund() ? omitRefund.contains(row.outRefundNo()) : omit.contains(row.outTradeNo())) continue;
            BillRow adjusted = row;
            String override = amount.get(row.key());
            if (override != null) adjusted = adjusted.withAmount(new BigDecimal(override));
            String status = refundStatus.get(row.key());
            if (status != null && row.refund()) adjusted = adjusted.withRefundStatus(status);
            rows.add(adjusted);
            if (duplicate.contains(row.key())) rows.add(adjusted);
        }
        for (BillRow row : extra) {
            if (row.time().toLocalDate().equals(date)) rows.add(row);
        }
        return rows;
    }

    private static void replace(Set<String> target, JsonNode patch, String field) {
        if (!patch.has(field)) return;
        target.clear();
        JsonNode value = patch.get(field);
        if (value.isArray()) for (JsonNode item : value) {
            String text = JsonNodes.text(item);
            if (text != null) target.add(text);
        }
    }

    private static void replace(Map<String, String> target, JsonNode patch, String field) {
        if (!patch.has(field)) return;
        target.clear();
        JsonNode value = patch.get(field);
        if (value.isObject()) value.properties().forEach(entry -> {
            String text = JsonNodes.text(entry.getValue());
            if (text != null) target.put(entry.getKey(), text);
        });
    }

    private static BillRow extraRow(JsonNode row) {
        String type = JsonNodes.text(row, "type");
        type = type == null ? "PAYMENT" : type.toUpperCase(Locale.ROOT);
        if (!type.equals("PAYMENT") && !type.equals("REFUND")) throw new IllegalArgumentException("extra.type 只能是 PAYMENT / REFUND");
        BigDecimal value = JsonNodes.decimal(row, "amount");
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("extra.amount 必须是正数");
        String time = JsonNodes.text(row, "time");
        LocalDateTime at = time == null ? LocalDateTime.now() : LocalDateTime.parse(time, TIME);
        String outTradeNo = JsonNodes.text(row, "outTradeNo");
        String tradeNo = JsonNodes.text(row, "tradeNo");
        String outRefundNo = JsonNodes.text(row, "outRefundNo");
        if (outTradeNo == null) throw new IllegalArgumentException("extra.outTradeNo 必填");
        if (tradeNo == null) tradeNo = "EXTRA-" + outTradeNo;
        if (type.equals("REFUND") && outRefundNo == null) outRefundNo = "EXTRA-RF-" + outTradeNo;
        String refundId = JsonNodes.text(row, "refundId");
        if (type.equals("REFUND") && refundId == null) refundId = "EXTRA-RFID-" + outTradeNo;
        String status = JsonNodes.text(row, "refundStatus");
        return new BillRow(type, outTradeNo, tradeNo, type.equals("REFUND") ? outRefundNo : null,
                type.equals("REFUND") ? refundId : null, value.setScale(2, java.math.RoundingMode.HALF_UP), at,
                type.equals("REFUND") ? (status == null ? "SUCCESS" : status) : null);
    }
}
