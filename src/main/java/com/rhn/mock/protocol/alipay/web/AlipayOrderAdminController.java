package com.rhn.mock.protocol.alipay.web;

import com.rhn.mock.core.JsonNodes;
import com.rhn.mock.protocol.alipay.AlipayGatewayService;
import com.rhn.mock.protocol.alipay.AlipayMockState;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * 支付宝订单管理端点 {@code /mock/alipay/trades|refunds}：查看交易 / 退款单状态、手动推进、清空。
 * 故障与处理中查询次数走通用的 {@code /mock/config?protocol=alipay}。
 */
@RestController
public class AlipayOrderAdminController {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Set<String> TRADE_TARGETS = Set.of("TRADE_SUCCESS", "TRADE_FINISHED", "TRADE_CLOSED");
    private static final Set<String> REFUND_TARGETS = Set.of("REFUND_SUCCESS", "FAILED");

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final AlipayMockState state;

    public AlipayOrderAdminController(AlipayMockState state) {
        this.state = state;
    }

    @GetMapping("/mock/alipay/trades")
    public ResponseEntity<String> trades() {
        ArrayNode array = NODES.arrayNode();
        state.allTransactions().stream().sorted(java.util.Comparator.comparing(AlipayMockState.Transaction::tradeNo))
                .forEach(transaction -> array.add(trade(transaction)));
        return json(HttpStatus.OK, array);
    }

    @GetMapping("/mock/alipay/trades/{outTradeNo}")
    public ResponseEntity<String> trade(@PathVariable String outTradeNo) {
        AlipayMockState.Transaction transaction = state.transaction(outTradeNo);
        if (transaction == null) return error(HttpStatus.NOT_FOUND, "交易不存在: " + outTradeNo);
        ObjectNode node = trade(transaction);
        ArrayNode refunds = node.putArray("refunds");
        state.refundsOf(transaction.tradeNo()).forEach(refund -> refunds.add(refund(refund)));
        return json(HttpStatus.OK, node);
    }

    /** 手动推进：body {@code {"to":"SUCCESS|FINISHED|CLOSED"}}。WAIT_BUYER_PAY → 任一；TRADE_SUCCESS → FINISHED / CLOSED；其余 409。 */
    @PostMapping("/mock/alipay/trades/{outTradeNo}/advance")
    public ResponseEntity<String> advanceTrade(@PathVariable String outTradeNo, @RequestBody(required = false) String body) {
        String target = target(body, "TRADE_");
        if (!TRADE_TARGETS.contains(target)) return error(HttpStatus.BAD_REQUEST, "to 只能是 SUCCESS / FINISHED / CLOSED");
        synchronized (state) {
            AlipayMockState.Transaction transaction = state.transaction(outTradeNo);
            if (transaction == null) return error(HttpStatus.NOT_FOUND, "交易不存在: " + outTradeNo);
            String from = transaction.tradeStatus();
            boolean allowed = "WAIT_BUYER_PAY".equals(from)
                    || ("TRADE_SUCCESS".equals(from) && !"TRADE_SUCCESS".equals(target));
            if (!allowed) return error(HttpStatus.CONFLICT, "不能从 " + from + " 推进到 " + target);
            state.putTransaction(AlipayGatewayService.resolved(transaction, target));
            return json(HttpStatus.OK, trade(state.transaction(outTradeNo)));
        }
    }

    @GetMapping("/mock/alipay/refunds")
    public ResponseEntity<String> refunds() {
        ArrayNode array = NODES.arrayNode();
        state.allRefunds().stream().sorted(java.util.Comparator.comparing(AlipayMockState.Refund::refundedAt))
                .forEach(refund -> array.add(refund(refund)));
        return json(HttpStatus.OK, array);
    }

    /** 手动推进退款单：body {@code {"to":"SUCCESS|FAILED"}}，仅 PROCESSING 可推进；out_request_no 跨交易重名时用 tradeNo 区分。 */
    @PostMapping("/mock/alipay/refunds/{outRequestNo}/advance")
    public ResponseEntity<String> advanceRefund(@PathVariable String outRequestNo,
                                                @RequestParam(value = "tradeNo", required = false) String tradeNo,
                                                @RequestBody(required = false) String body) {
        String target = target(body, "REFUND_");
        if (!REFUND_TARGETS.contains(target)) return error(HttpStatus.BAD_REQUEST, "to 只能是 SUCCESS / FAILED");
        synchronized (state) {
            AlipayMockState.Refund found = state.allRefunds().stream()
                    .filter(refund -> refund.outRequestNo().equals(outRequestNo)
                            && (tradeNo == null || refund.tradeNo().equals(tradeNo)))
                    .findFirst().orElse(null);
            if (found == null) return error(HttpStatus.NOT_FOUND, "退款单不存在: " + outRequestNo);
            if (!"PROCESSING".equals(found.refundStatus())) {
                return error(HttpStatus.CONFLICT, "退款单已是终态 " + found.refundStatus());
            }
            AlipayMockState.Refund next = new AlipayMockState.Refund(found.tradeNo(), found.outRequestNo(),
                    found.refundAmount(), target, LocalDateTime.now());
            state.putRefund(next);
            return json(HttpStatus.OK, refund(next));
        }
    }

    /** 清空全部交易与退款单（保留故障配置、账单差异；全量复位用 POST /mock/reset）。 */
    @DeleteMapping("/mock/alipay/trades")
    public ResponseEntity<String> clear() {
        synchronized (state) {
            state.clearOrders();
        }
        return json(HttpStatus.OK, NODES.objectNode().put("status", "OK"));
    }

    private static ObjectNode trade(AlipayMockState.Transaction transaction) {
        ObjectNode node = NODES.objectNode();
        node.put("outTradeNo", transaction.outTradeNo());
        node.put("tradeNo", transaction.tradeNo());
        node.put("totalAmount", transaction.totalAmount());
        node.put("tradeStatus", transaction.tradeStatus());
        node.put("paidAt", String.valueOf(transaction.paidAt()));
        node.put("resolveTo", transaction.resolveTo());
        node.put("queriesLeft", transaction.queriesLeft());
        return node;
    }

    private static ObjectNode refund(AlipayMockState.Refund refund) {
        ObjectNode node = NODES.objectNode();
        node.put("tradeNo", refund.tradeNo());
        node.put("outRequestNo", refund.outRequestNo());
        node.put("refundAmount", refund.refundAmount());
        node.put("refundStatus", refund.refundStatus());
        node.put("refundedAt", String.valueOf(refund.refundedAt()));
        node.put("resolveTo", refund.resolveTo());
        node.put("queriesLeft", refund.queriesLeft());
        return node;
    }

    /** 目标状态：接受 SUCCESS 或全名 TRADE_SUCCESS，统一补前缀。 */
    private String target(String body, String prefix) {
        try {
            JsonNode node = body == null || body.isBlank() ? null : mapper.readTree(body);
            String value = JsonNodes.text(node, "to");
            if (value == null) return "";
            String upper = value.toUpperCase(Locale.ROOT);
            return upper.startsWith(prefix) || "FAILED".equals(upper) ? upper : prefix + upper;
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
