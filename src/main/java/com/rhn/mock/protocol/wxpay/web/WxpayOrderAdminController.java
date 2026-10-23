package com.rhn.mock.protocol.wxpay.web;

import com.rhn.mock.core.JsonNodes;
import com.rhn.mock.protocol.wxpay.WxpayGatewayService;
import com.rhn.mock.protocol.wxpay.WxpayMockState;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * 微信订单管理端点 {@code /mock/wxpay/orders|refunds}：查看订单 / 退款单状态、手动推进、清空。
 * 故障与处理中查询次数走通用的 {@code /mock/config?protocol=wxpay}。
 */
@RestController
public class WxpayOrderAdminController {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final DateTimeFormatter TIME_END = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> ORDER_TARGETS = Set.of("SUCCESS", "CLOSED", "REVOKED", "PAYERROR", "USERPAYING", "NOTPAY");
    private static final Set<String> REFUND_TARGETS = Set.of("SUCCESS", "REFUNDCLOSE", "CHANGE");

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final WxpayMockState state;

    public WxpayOrderAdminController(WxpayMockState state) {
        this.state = state;
    }

    @GetMapping("/mock/wxpay/orders")
    public ResponseEntity<String> orders() {
        ArrayNode array = NODES.arrayNode();
        state.allTransactions().stream().sorted(java.util.Comparator.comparing(WxpayMockState.Transaction::transactionId))
                .forEach(transaction -> array.add(order(transaction)));
        return json(HttpStatus.OK, array);
    }

    @GetMapping("/mock/wxpay/orders/{outTradeNo}")
    public ResponseEntity<String> order(@PathVariable String outTradeNo) {
        WxpayMockState.Transaction transaction = state.transactionByOutTradeNo(outTradeNo);
        if (transaction == null) return error(HttpStatus.NOT_FOUND, "订单不存在: " + outTradeNo);
        ObjectNode node = order(transaction);
        ArrayNode refunds = node.putArray("refunds");
        state.refundsOfTransaction(transaction.transactionId()).forEach(refund -> refunds.add(refund(refund)));
        return json(HttpStatus.OK, node);
    }

    /** 手动推进：body {@code {"to":"SUCCESS|CLOSED|REVOKED|PAYERROR|USERPAYING|NOTPAY"}}。仅允许从 USERPAYING / NOTPAY 出发，或 SUCCESS → REVOKED / CLOSED。 */
    @PostMapping("/mock/wxpay/orders/{outTradeNo}/advance")
    public ResponseEntity<String> advanceOrder(@PathVariable String outTradeNo, @RequestBody(required = false) String body) {
        String target = target(body);
        if (!ORDER_TARGETS.contains(target)) return error(HttpStatus.BAD_REQUEST, "to 只能是 " + ORDER_TARGETS);
        synchronized (state) {
            WxpayMockState.Transaction transaction = state.transactionByOutTradeNo(outTradeNo);
            if (transaction == null) return error(HttpStatus.NOT_FOUND, "订单不存在: " + outTradeNo);
            String from = transaction.tradeState();
            boolean allowed = ("USERPAYING".equals(from) || "NOTPAY".equals(from)) && !from.equals(target)
                    || ("SUCCESS".equals(from) && Set.of("REVOKED", "CLOSED").contains(target));
            if (!allowed) return error(HttpStatus.CONFLICT, "不能从 " + from + " 推进到 " + target);
            state.putTransaction(WxpayGatewayService.resolved(transaction, target));
            return json(HttpStatus.OK, order(state.transactionByOutTradeNo(outTradeNo)));
        }
    }

    @GetMapping("/mock/wxpay/refunds")
    public ResponseEntity<String> refunds() {
        ArrayNode array = NODES.arrayNode();
        state.allRefunds().stream().sorted(java.util.Comparator.comparing(WxpayMockState.Refund::refundId))
                .forEach(refund -> array.add(refund(refund)));
        return json(HttpStatus.OK, array);
    }

    /** 手动推进退款单：body {@code {"to":"SUCCESS|REFUNDCLOSE|CHANGE"}}，仅 PROCESSING 可推进。 */
    @PostMapping("/mock/wxpay/refunds/{outRefundNo}/advance")
    public ResponseEntity<String> advanceRefund(@PathVariable String outRefundNo, @RequestBody(required = false) String body) {
        String target = target(body);
        if (!REFUND_TARGETS.contains(target)) return error(HttpStatus.BAD_REQUEST, "to 只能是 " + REFUND_TARGETS);
        synchronized (state) {
            WxpayMockState.Refund found = state.refundByOutRefundNo(outRefundNo);
            if (found == null) return error(HttpStatus.NOT_FOUND, "退款单不存在: " + outRefundNo);
            if (!"PROCESSING".equals(found.refundStatus())) {
                return error(HttpStatus.CONFLICT, "退款单已是终态 " + found.refundStatus());
            }
            WxpayMockState.Refund next = new WxpayMockState.Refund(found.transactionId(), found.outTradeNo(), found.outRefundNo(),
                    found.refundId(), found.refundFee(), found.totalFee(), target, LocalDateTime.now().format(TIME_END));
            state.putRefund(next);
            return json(HttpStatus.OK, refund(next));
        }
    }

    /** 清空全部订单与退款单（保留故障配置、账单差异；全量复位用 POST /mock/reset）。 */
    @DeleteMapping("/mock/wxpay/orders")
    public ResponseEntity<String> clear() {
        synchronized (state) {
            state.clearOrders();
        }
        return json(HttpStatus.OK, NODES.objectNode().put("status", "OK"));
    }

    private static ObjectNode order(WxpayMockState.Transaction transaction) {
        ObjectNode node = NODES.objectNode();
        node.put("outTradeNo", transaction.outTradeNo());
        node.put("transactionId", transaction.transactionId());
        node.put("totalFee", transaction.totalFee());
        node.put("tradeState", transaction.tradeState());
        node.put("timeEnd", transaction.timeEnd());
        node.put("resolveTo", transaction.resolveTo());
        node.put("queriesLeft", transaction.queriesLeft());
        return node;
    }

    private static ObjectNode refund(WxpayMockState.Refund refund) {
        ObjectNode node = NODES.objectNode();
        node.put("outTradeNo", refund.outTradeNo());
        node.put("transactionId", refund.transactionId());
        node.put("outRefundNo", refund.outRefundNo());
        node.put("refundId", refund.refundId());
        node.put("refundFee", refund.refundFee());
        node.put("refundStatus", refund.refundStatus());
        node.put("refundTime", refund.refundTime());
        node.put("resolveTo", refund.resolveTo());
        node.put("queriesLeft", refund.queriesLeft());
        return node;
    }

    private String target(String body) {
        try {
            JsonNode node = body == null || body.isBlank() ? null : mapper.readTree(body);
            String value = JsonNodes.text(node, "to");
            return value == null ? "" : value.toUpperCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private ResponseEntity<String> error(HttpStatus status, String message) {
        return json(status, NODES.objectNode().put("error", message));
    }

    private ResponseEntity<String> json(HttpStatus status, JsonNode node) {
        return ResponseEntity.status(status).contentType(MediaType.valueOf("application/json;charset=UTF-8"))
                .body(mapper.writeValueAsString(node));
    }
}
