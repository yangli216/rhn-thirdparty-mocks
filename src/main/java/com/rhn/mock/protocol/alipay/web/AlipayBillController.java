package com.rhn.mock.protocol.alipay.web;

import com.rhn.mock.core.bill.BillRow;
import com.rhn.mock.protocol.alipay.AlipayBillService;
import com.rhn.mock.protocol.alipay.AlipayMockState;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;

/**
 * 支付宝对账单端点：账单文件下载 {@code GET /alipay/bill/download/{date}}（链接查询返回的地址），
 * 以及差异构造 / 预览 {@code /mock/alipay/bill/**}。
 */
@RestController
public class AlipayBillController {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final AlipayBillService bills;
    private final AlipayMockState state;

    public AlipayBillController(AlipayBillService bills, AlipayMockState state) {
        this.bills = bills;
        this.state = state;
    }

    @GetMapping("/alipay/bill/download/{date}")
    public ResponseEntity<byte[]> download(@PathVariable String date) {
        LocalDate day = LocalDate.parse(date);
        return ResponseEntity.ok().contentType(MediaType.valueOf("application/zip")).body(bills.zip(day));
    }

    @GetMapping("/mock/alipay/bill/anomalies")
    public ResponseEntity<String> anomalies() {
        return json(HttpStatus.OK, state.bill().view());
    }

    /** 部分更新差异配置，见 {@code BillAnomalies}；非法值返回 400。 */
    @PutMapping("/mock/alipay/bill/anomalies")
    public ResponseEntity<String> update(@RequestBody(required = false) String body) {
        try {
            state.bill().update(body == null || body.isBlank() ? NODES.objectNode() : mapper.readTree(body));
        } catch (RuntimeException exception) {
            return json(HttpStatus.BAD_REQUEST, NODES.objectNode().put("error", String.valueOf(exception.getMessage())));
        }
        return json(HttpStatus.OK, state.bill().view());
    }

    @DeleteMapping("/mock/alipay/bill/anomalies")
    public ResponseEntity<String> clear() {
        state.bill().clear();
        return json(HttpStatus.OK, state.bill().view());
    }

    /** 预览某日最终账单行与 CSV 原文（不经过 zip / GBK）。 */
    @GetMapping("/mock/alipay/bill/{date}")
    public ResponseEntity<String> preview(@PathVariable String date) {
        LocalDate day = LocalDate.parse(date);
        ObjectNode node = NODES.objectNode();
        ArrayNode rows = node.putArray("rows");
        for (BillRow row : bills.rows(day)) {
            ObjectNode item = rows.addObject();
            item.put("type", row.type());
            item.put("outTradeNo", row.outTradeNo());
            item.put("tradeNo", row.tradeNo());
            item.put("outRefundNo", row.outRefundNo());
            item.put("amount", row.amount().toPlainString());
            item.put("time", row.time().toString());
        }
        node.put("csv", bills.csv(day));
        return json(HttpStatus.OK, node);
    }

    private ResponseEntity<String> json(HttpStatus status, tools.jackson.databind.JsonNode node) {
        return ResponseEntity.status(status).contentType(MediaType.valueOf("application/json;charset=UTF-8"))
                .body(mapper.writeValueAsString(node));
    }
}
