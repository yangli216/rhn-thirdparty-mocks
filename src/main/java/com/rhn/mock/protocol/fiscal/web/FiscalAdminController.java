package com.rhn.mock.protocol.fiscal.web;

import com.rhn.mock.core.JsonNodes;
import com.rhn.mock.protocol.fiscal.FiscalCallbackService;
import com.rhn.mock.protocol.fiscal.FiscalMockEngine;
import com.rhn.mock.protocol.fiscal.FiscalMockState;
import com.rhn.mock.protocol.fiscal.FiscalReceipt;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.util.Locale;
import java.util.Optional;

/**
 * 财政票据测试辅助端点 {@code /mock/fiscal/**}：查看票据、手动完成处理中的票据、手动触发回调（正常 / 重复 / 签名错误）、
 * 查看回调投递记录；以及供开具结果里 verifyUrl 指向的查验页 {@code <base-path>/verify}。
 */
@RestController
public class FiscalAdminController {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final FiscalMockState state;
    private final FiscalMockEngine engine;
    private final FiscalCallbackService callbacks;

    public FiscalAdminController(FiscalMockState state, FiscalMockEngine engine, FiscalCallbackService callbacks) {
        this.state = state;
        this.engine = engine;
        this.callbacks = callbacks;
    }

    @GetMapping("/mock/fiscal/receipts")
    public ResponseEntity<String> receipts() {
        ArrayNode array = NODES.arrayNode();
        state.receipts().forEach(receipt -> array.add(view(receipt)));
        return json(HttpStatus.OK, array);
    }

    /** ref 可以是外部票据号或申请号。 */
    @GetMapping("/mock/fiscal/receipts/{ref}")
    public ResponseEntity<String> receipt(@PathVariable String ref) {
        FiscalReceipt receipt = state.find(ref, ref);
        return receipt == null ? notFound(ref) : json(HttpStatus.OK, view(receipt));
    }

    /**
     * 完成处理中的票据：body {@code {"success":true|false,"push":true|false}}（默认成功并推送回调）。
     * 未配置回调地址时只改状态，由 RHN 主动 query 取回。
     */
    @PostMapping("/mock/fiscal/receipts/{ref}/complete")
    public ResponseEntity<String> complete(@PathVariable String ref, @RequestBody(required = false) String body) {
        FiscalReceipt receipt = state.find(ref, ref);
        if (receipt == null) return notFound(ref);
        JsonNode node = parse(body);
        boolean success = !"false".equalsIgnoreCase(JsonNodes.text(node, "success"));
        boolean push = !"false".equalsIgnoreCase(JsonNodes.text(node, "push"));
        engine.complete(receipt, success);
        ObjectNode out = view(receipt);
        if (push) callbacks.push(receipt, FiscalCallbackService.Mode.NORMAL, JsonNodes.text(node, "callbackUrl"))
                .ifPresent(delivery -> out.set("delivery", mapper.valueToTree(delivery)));
        return json(HttpStatus.OK, out);
    }

    /** 按票据当前状态手动推送回调：body {@code {"mode":"normal|duplicate|bad_signature","callbackUrl":"可选"}}。 */
    @PostMapping("/mock/fiscal/receipts/{ref}/callback")
    public ResponseEntity<String> callback(@PathVariable String ref, @RequestBody(required = false) String body) {
        FiscalReceipt receipt = state.find(ref, ref);
        if (receipt == null) return notFound(ref);
        JsonNode node = parse(body);
        String modeText = JsonNodes.text(node, "mode");
        FiscalCallbackService.Mode mode;
        try {
            mode = modeText == null ? FiscalCallbackService.Mode.NORMAL
                    : FiscalCallbackService.Mode.valueOf(modeText.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return json(HttpStatus.BAD_REQUEST, NODES.objectNode().put("error", "mode 只能是 normal / duplicate / bad_signature"));
        }
        Optional<FiscalMockState.CallbackDelivery> delivery = callbacks.push(receipt, mode, JsonNodes.text(node, "callbackUrl"));
        if (delivery.isEmpty()) {
            return json(HttpStatus.CONFLICT, NODES.objectNode().put("error", "未配置回调地址：PUT /mock/config?protocol=fiscal {\"callbackUrl\":...} 或在 body 传 callbackUrl"));
        }
        return json(HttpStatus.OK, mapper.valueToTree(delivery.get()));
    }

    @GetMapping("/mock/fiscal/callbacks")
    public ResponseEntity<String> deliveries() {
        return json(HttpStatus.OK, mapper.valueToTree(state.deliveries()));
    }

    /** verifyUrl 指向的公共查验页（JSON 形式）：校验码不符或票据不存在返回 404。 */
    @GetMapping("${mock.fiscal.base-path:/fiscal}/verify")
    public ResponseEntity<String> verify(@RequestParam("bill_code") String code, @RequestParam("bill_no") String number,
                                         @RequestParam("check_code") String check) {
        for (FiscalReceipt receipt : state.receipts()) {
            if (receipt.fiscalCode().equals(code) && receipt.fiscalNumber().equals(number)
                    && receipt.verificationCode().equals(check)) {
                ObjectNode node = view(receipt);
                node.put("verified", true);
                return json(HttpStatus.OK, node);
            }
        }
        return json(HttpStatus.NOT_FOUND, NODES.objectNode().put("verified", false));
    }

    private ObjectNode view(FiscalReceipt receipt) {
        ObjectNode node = NODES.objectNode();
        node.put("externalReceiptNo", receipt.externalReceiptNo());
        node.put("kind", receipt.red() ? "RED" : "BLUE");
        node.put("status", receipt.status().name());
        node.put("receiptRequestNo", receipt.receiptRequestNo());
        node.put("idempotencyKey", receipt.idempotencyKey());
        node.put("payerName", receipt.payerName());
        node.put("amount", receipt.amount().toPlainString());
        node.put("fiscalCode", receipt.fiscalCode());
        node.put("fiscalNumber", receipt.fiscalNumber());
        node.put("verificationCode", receipt.verificationCode());
        node.put("verifyUrl", receipt.verifyUrl());
        node.put("issuedAt", receipt.issuedAt() == null ? null : receipt.issuedAt().toString());
        node.put("voidedAt", receipt.voidedAt() == null ? null : receipt.voidedAt().toString());
        node.put("originalExternalReceiptNo", receipt.originalExternalReceiptNo());
        node.put("redExternalReceiptNo", receipt.redExternalReceiptNo());
        node.put("queryCount", receipt.queryCount());
        return node;
    }

    private JsonNode parse(String body) {
        return body == null || body.isBlank() ? NODES.objectNode() : mapper.readTree(body);
    }

    private ResponseEntity<String> notFound(String ref) {
        return json(HttpStatus.NOT_FOUND, NODES.objectNode().put("error", "未找到票据 " + ref));
    }

    private ResponseEntity<String> json(HttpStatus status, JsonNode node) {
        return ResponseEntity.status(status).contentType(MediaType.valueOf("application/json;charset=UTF-8"))
                .body(mapper.writeValueAsString(node));
    }
}
