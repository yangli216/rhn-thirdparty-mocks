package com.rhn.mock.protocol.wxpay.web;

import com.rhn.mock.core.bill.BillRow;
import com.rhn.mock.protocol.wxpay.WxpayBillService;
import com.rhn.mock.protocol.wxpay.WxpayMockState;
import com.rhn.mock.protocol.wxpay.config.WxpayMockProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;

/** 微信对账单差异构造 / 预览 {@code /mock/wxpay/bill/**}（账单本身走 {@code POST /wxpay/pay/downloadbill}）。 */
@RestController
public class WxpayBillController {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final WxpayBillService bills;
    private final WxpayMockState state;
    private final WxpayMockProperties properties;

    public WxpayBillController(WxpayBillService bills, WxpayMockState state, WxpayMockProperties properties) {
        this.bills = bills;
        this.state = state;
        this.properties = properties;
    }

    @GetMapping("/mock/wxpay/bill/anomalies")
    public ResponseEntity<String> anomalies() {
        return json(HttpStatus.OK, state.bill().view());
    }

    @PutMapping("/mock/wxpay/bill/anomalies")
    public ResponseEntity<String> update(@RequestBody(required = false) String body) {
        try {
            state.bill().update(body == null || body.isBlank() ? NODES.objectNode() : mapper.readTree(body));
        } catch (RuntimeException exception) {
            return json(HttpStatus.BAD_REQUEST, NODES.objectNode().put("error", String.valueOf(exception.getMessage())));
        }
        return json(HttpStatus.OK, state.bill().view());
    }

    @DeleteMapping("/mock/wxpay/bill/anomalies")
    public ResponseEntity<String> clear() {
        state.bill().clear();
        return json(HttpStatus.OK, state.bill().view());
    }

    @GetMapping("/mock/wxpay/bill/{date}")
    public ResponseEntity<String> preview(@PathVariable String date) {
        LocalDate day = LocalDate.parse(date);
        ObjectNode node = NODES.objectNode();
        ArrayNode rows = node.putArray("rows");
        for (BillRow row : bills.rows(day)) {
            ObjectNode item = rows.addObject();
            item.put("type", row.type());
            item.put("outTradeNo", row.outTradeNo());
            item.put("transactionId", row.tradeNo());
            item.put("outRefundNo", row.outRefundNo());
            item.put("amount", row.amount().toPlainString());
            item.put("refundStatus", row.refundStatus());
            item.put("time", row.time().toString());
        }
        node.put("csv", bills.csv(day, properties.appId(), properties.mchId(), false));
        return json(HttpStatus.OK, node);
    }

    private ResponseEntity<String> json(HttpStatus status, JsonNode node) {
        return ResponseEntity.status(status).contentType(MediaType.valueOf("application/json;charset=UTF-8"))
                .body(mapper.writeValueAsString(node));
    }
}
