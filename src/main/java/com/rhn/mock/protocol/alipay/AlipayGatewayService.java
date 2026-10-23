package com.rhn.mock.protocol.alipay;

import com.rhn.mock.core.ExchangeLog;
import com.rhn.mock.core.JsonNodes;
import com.rhn.mock.core.MockHttpResponse;
import com.rhn.mock.core.fault.PaymentScenarios;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 支付宝开放平台网关仿真。
 *
 * <p>请求参数同时来自 query 与 form（真实 SDK 把协议参数放 query、{@code biz_content} 放 form）。
 * 响应必须是 {@code {"<method 点换下划线>_response":{…},"sign":"<base64 RSA2>"}}，
 * 且签名对象是内层 JSON 的**发出原文**——先用同一个字符串加签，再手工拼进外壳，不能二次序列化。
 */
@Service
public class AlipayGatewayService {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> METHODS = Set.of(
            "alipay.trade.pay", "alipay.trade.query", "alipay.trade.refund", "alipay.trade.fastpay.refund.query",
            "alipay.data.dataservice.bill.downloadurl.query");

    private final AlipaySigner signer;
    private final AlipayMockState state;
    private final ExchangeLog log;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final AlipayBillService bills;

    public AlipayGatewayService(AlipaySigner signer, AlipayMockState state, ExchangeLog log, AlipayBillService bills) {
        this.signer = signer;
        this.state = state;
        this.log = log;
        this.bills = bills;
    }

    public MockHttpResponse handle(Map<String, String> params) {
        String method = text(params.get("method"));
        JsonNode biz = bizContent(params);
        String reference = reference(biz);
        String fault = state.faults().take(opKey(method), reference);
        MockHttpResponse response = faulted(fault)
                ? faultResponse(fault)
                : MockHttpResponse.json(dispatch(method, biz, params));
        if (fault != null) response = applyFault(fault, response);
        log.add(new ExchangeLog.Entry(LocalDateTime.now().format(TIME), AlipayProtocol.ID,
                method == null ? "" : method, reference, response.httpStatus(),
                mapper.writeValueAsString(new TreeMap<>(params)),
                (fault == null ? "" : "[fault=" + fault + "] ") + response.body()));
        return response;
    }

    private String dispatch(String method, JsonNode biz, Map<String, String> params) {
        if (state.requestSignRequired() && !requestSigned(params)) {
            return businessError("error_response", "40002", "isv.invalid-signature", "请求签名校验未通过");
        }
        if (method == null || !METHODS.contains(method)) {
            return businessError("error_response", "40004", "isv.invalid-method", "不支持的接口: " + method);
        }
        synchronized (state) {
            return switch (method) {
                case "alipay.trade.pay" -> pay(biz);
                case "alipay.trade.query" -> query(biz);
                case "alipay.trade.refund" -> refund(biz);
                case "alipay.data.dataservice.bill.downloadurl.query" -> billDownloadUrl(biz);
                default -> refundQuery(biz);
            };
        }
    }

    /** 请求根本没被处理的故障（应答阶段故障 faultProcessed=true 时业务照常执行，只是应答丢失/损坏）。 */
    private boolean faulted(String fault) {
        return fault != null && !state.faults().faultProcessed() && Set.of("HTTP500", "BADBODY", "TIMEOUT").contains(fault);
    }

    private MockHttpResponse faultResponse(String fault) {
        return applyFault(fault, MockHttpResponse.json("{}"));
    }

    private MockHttpResponse applyFault(String fault, MockHttpResponse normal) {
        return switch (fault) {
            case "HTTP500" -> MockHttpResponse.raw(500, "{\"error\":\"simulated alipay http 500\"}", "application/json;charset=UTF-8");
            case "BADBODY" -> MockHttpResponse.raw(200, "<html>not a json response</html>", "text/html;charset=UTF-8");
            case "SLOW" -> {
                sleep(state.faults().slowDelayMs());
                yield normal;
            }
            case "TIMEOUT" -> {
                sleep(state.faults().timeoutDelayMs());
                yield normal;
            }
            case "NOSIGN" -> MockHttpResponse.json(stripSign(normal.body()));
            case "BADSIGN" -> MockHttpResponse.json(badSign(normal.body()));
            default -> normal;
        };
    }

    /** 去掉外壳里的 sign 字段（内层原文不变）。 */
    private static String stripSign(String body) {
        int index = body.lastIndexOf(",\"sign\":");
        return index < 0 ? body : body.substring(0, index) + "}";
    }

    /** 保留一个格式合法、但不是对本内层原文所加的签名：验签必然失败。 */
    private String badSign(String body) {
        int index = body.lastIndexOf(",\"sign\":");
        if (index < 0) return body;
        return body.substring(0, index) + ",\"sign\":\"" + signer.sign("tampered:" + body.substring(0, index)) + "\"}";
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String opKey(String method) {
        if (method == null) return "";
        return switch (method) {
            case "alipay.trade.pay" -> "pay";
            case "alipay.trade.query" -> "query";
            case "alipay.trade.refund" -> "refund";
            case "alipay.trade.fastpay.refund.query" -> "refundquery";
            case "alipay.data.dataservice.bill.downloadurl.query" -> "bill";
            default -> method;
        };
    }

    private String pay(JsonNode biz) {
        String outTradeNo = text(biz, "out_trade_no");
        String totalAmount = text(biz, "total_amount");
        String authCode = text(biz, "auth_code");
        if (outTradeNo == null || totalAmount == null) {
            return businessError("alipay_trade_pay_response", "40004", "isv.missing-parameter", "缺少 out_trade_no/total_amount");
        }
        if (authCode != null && authCode.startsWith("SIM_FAIL_")) {
            return businessError("alipay_trade_pay_response", "40004", "ACQ.TRADE_HAS_CLOSE", "模拟支付失败");
        }
        if (authCode != null && authCode.startsWith("SIM_PAYING_")) {
            return envelope("alipay_trade_pay_response", node -> node
                    .put("code", "10003").put("msg", "Service Currently Unavailable")
                    .put("sub_code", "ACQ.SYSTEM_ERROR").put("sub_msg", "模拟渠道处理中")
                    .put("out_trade_no", outTradeNo));
        }
        AlipayMockState.Transaction existing = state.transaction(outTradeNo);
        if (existing != null) {
            // 同一 out_trade_no 重复发起：支付宝按既有交易状态应答，不会重新扣款。
            if ("WAIT_BUYER_PAY".equals(existing.tradeStatus())) return waitBuyerPay(existing);
            if (existing.paid()) {
                return businessError("alipay_trade_pay_response", "40004", "ACQ.TRADE_HAS_SUCCESS", "交易已被支付");
            }
            return businessError("alipay_trade_pay_response", "40004", "ACQ.TRADE_HAS_CLOSE", "交易已经关闭");
        }
        String scenario = PaymentScenarios.pay(outTradeNo, totalAmount);
        if (scenario == null) scenario = "";
        switch (scenario) {
            case "NOFUNDS" -> {
                return businessError("alipay_trade_pay_response", "40004", "ACQ.BUYER_BALANCE_NOT_ENOUGH", "买家余额不足");
            }
            case "BADCODE" -> {
                return businessError("alipay_trade_pay_response", "40004", "ACQ.PAYMENT_AUTH_CODE_INVALID",
                        "支付失败，获取顾客账户信息失败，请顾客刷新付款码后重新收款");
            }
            case "UNKNOWN_UNPAID" -> {
                return unknown("alipay_trade_pay_response", outTradeNo);
            }
            case "PAYING", "PAYING_SUCCESS", "PAYING_CLOSED", "PAYING_FAILED" -> {
                String resolveTo = switch (scenario) {
                    case "PAYING_SUCCESS" -> "TRADE_SUCCESS";
                    case "PAYING_CLOSED", "PAYING_FAILED" -> "TRADE_CLOSED";
                    default -> null;
                };
                AlipayMockState.Transaction waiting = new AlipayMockState.Transaction(outTradeNo, state.nextTradeNo(),
                        totalAmount, "CNY", "WAIT_BUYER_PAY", LocalDateTime.now(), resolveTo, state.faults().pendingQueries());
                state.putTransaction(waiting);
                return waitBuyerPay(waiting);
            }
            default -> {
            }
        }
        String tradeNo = state.nextTradeNo();
        String status = switch (scenario) {
            case "FINISHED" -> "TRADE_FINISHED";
            default -> outTradeNo.endsWith("-CLOSED") ? "TRADE_CLOSED" : "TRADE_SUCCESS";
        };
        state.putTransaction(new AlipayMockState.Transaction(outTradeNo, tradeNo, totalAmount, "CNY", status, LocalDateTime.now()));
        if ("UNKNOWN_PAID".equals(scenario)) return unknown("alipay_trade_pay_response", outTradeNo);
        return envelope("alipay_trade_pay_response", node -> {
            node.put("code", "10000").put("msg", "Success");
            node.put("out_trade_no", outTradeNo);
            node.put("trade_no", tradeNo);
            node.put("trade_status", status);
            node.put("total_amount", totalAmount);
            node.put("trans_currency", "CNY");
        });
    }

    /** 付款码支付等待买家输入密码：10003，需继续轮询 query。 */
    private String waitBuyerPay(AlipayMockState.Transaction transaction) {
        return envelope("alipay_trade_pay_response", node -> node
                .put("code", "10003").put("msg", "order success pay inprocess")
                .put("out_trade_no", transaction.outTradeNo()).put("trade_no", transaction.tradeNo()));
    }

    /** 结果未知：20000 ACQ.SYSTEM_ERROR，商户必须查单确认。 */
    private String unknown(String responseType, String outTradeNo) {
        return envelope(responseType, node -> node
                .put("code", "20000").put("msg", "Service Currently Unavailable")
                .put("sub_code", "ACQ.SYSTEM_ERROR").put("sub_msg", "系统繁忙，结果未知，请查询订单")
                .put("out_trade_no", outTradeNo == null ? "" : outTradeNo));
    }

    private String query(JsonNode biz) {
        String outTradeNo = text(biz, "out_trade_no");
        String tradeNo = text(biz, "trade_no");
        AlipayMockState.Transaction transaction = outTradeNo != null
                ? state.transaction(outTradeNo) : state.transactionByTradeNo(tradeNo);
        if (transaction == null) {
            return tradeNotExist("alipay_trade_query_response", outTradeNo == null ? tradeNo : outTradeNo);
        }
        AlipayMockState.Transaction current = progress(transaction);
        return envelope("alipay_trade_query_response", node -> {
            node.put("code", "10000").put("msg", "Success");
            node.put("out_trade_no", current.outTradeNo());
            node.put("trade_no", current.tradeNo());
            node.put("trade_status", current.tradeStatus());
            node.put("total_amount", current.totalAmount());
            node.put("trans_currency", current.currency());
        });
    }

    /** 每次查询推进一步：等待买家付款的订单查满 queriesLeft 次后落到 resolveTo。 */
    private AlipayMockState.Transaction progress(AlipayMockState.Transaction transaction) {
        if (!"WAIT_BUYER_PAY".equals(transaction.tradeStatus()) || transaction.resolveTo() == null) return transaction;
        AlipayMockState.Transaction next;
        if (transaction.queriesLeft() > 0) {
            next = new AlipayMockState.Transaction(transaction.outTradeNo(), transaction.tradeNo(), transaction.totalAmount(),
                    transaction.currency(), transaction.tradeStatus(), transaction.paidAt(), transaction.resolveTo(),
                    transaction.queriesLeft() - 1);
        } else {
            next = resolved(transaction, transaction.resolveTo());
        }
        state.putTransaction(next);
        return next;
    }

    public static AlipayMockState.Transaction resolved(AlipayMockState.Transaction transaction, String status) {
        return new AlipayMockState.Transaction(transaction.outTradeNo(), transaction.tradeNo(), transaction.totalAmount(),
                transaction.currency(), status, "TRADE_CLOSED".equals(status) ? transaction.paidAt() : LocalDateTime.now());
    }

    private String refund(JsonNode biz) {
        String tradeNo = text(biz, "trade_no");
        String outRequestNo = text(biz, "out_request_no");
        String refundAmount = text(biz, "refund_amount");
        AlipayMockState.Transaction transaction = tradeNo != null ? state.transactionByTradeNo(tradeNo)
                : state.transaction(text(biz, "out_trade_no"));
        if (transaction == null) return tradeNotExist("alipay_trade_refund_response", tradeNo);
        String type = "alipay_trade_refund_response";
        if (outRequestNo == null || refundAmount == null) {
            return businessError(type, "40004", "isv.missing-parameter", "缺少 out_request_no/refund_amount");
        }
        BigDecimal amount = decimal(refundAmount);
        if (amount == null || amount.signum() <= 0) {
            return businessError(type, "40004", "ACQ.INVALID_PARAMETER", "退款金额不合法");
        }
        if (!transaction.paid()) {
            return businessError(type, "40004", "ACQ.TRADE_STATUS_ERROR", "交易状态不合法");
        }
        String actualTradeNo = transaction.tradeNo();
        AlipayMockState.Refund existing = state.refund(actualTradeNo, outRequestNo);
        if (existing != null) {
            // 幂等：同一 out_request_no 同金额重复提交返回同一结果且不再动资金；金额不同视为参数冲突。
            if (decimal(existing.refundAmount()).compareTo(amount) != 0) {
                return businessError(type, "40004", "ACQ.INVALID_PARAMETER", "同一 out_request_no 的退款金额与首次不一致");
            }
            return refundResponse(type, transaction, existing, "N");
        }
        BigDecimal refunded = BigDecimal.ZERO;
        for (AlipayMockState.Refund each : state.refundsOf(actualTradeNo)) {
            if (!"FAILED".equals(each.refundStatus())) refunded = refunded.add(decimal(each.refundAmount()));
        }
        if (refunded.add(amount).compareTo(decimal(transaction.totalAmount())) > 0) {
            return businessError(type, "40004", "ACQ.REFUND_AMT_NOT_EQUAL_TOTAL", "退款金额超限");
        }
        String scenario = PaymentScenarios.refund(outRequestNo, refundAmount);
        if ("REFUND_REJECTED".equals(scenario)) {
            return businessError(type, "40004", "ACQ.SELLER_BALANCE_NOT_ENOUGH", "商户的支付宝账户中余额不足");
        }
        String status = "REFUND_SUCCESS";
        String resolveTo = null;
        int left = 0;
        if (scenario != null && scenario.startsWith("REFUND_") && !"REFUND_UNKNOWN".equals(scenario)) {
            status = "PROCESSING";
            resolveTo = switch (scenario) {
                case "REFUND_SUCCESS_LATER" -> "REFUND_SUCCESS";
                case "REFUND_CLOSED_LATER" -> "FAILED";
                default -> null;
            };
            left = state.faults().pendingQueries();
        }
        AlipayMockState.Refund created = new AlipayMockState.Refund(actualTradeNo, outRequestNo, refundAmount, status,
                LocalDateTime.now(), resolveTo, left);
        state.putRefund(created);
        if ("REFUND_UNKNOWN".equals(scenario)) return unknown(type, transaction.outTradeNo());
        return refundResponse(type, transaction, created, "REFUND_SUCCESS".equals(status) ? "Y" : "N");
    }

    private String refundResponse(String type, AlipayMockState.Transaction transaction, AlipayMockState.Refund refund,
                                  String fundChange) {
        BigDecimal total = BigDecimal.ZERO;
        for (AlipayMockState.Refund each : state.refundsOf(transaction.tradeNo())) {
            if (!"FAILED".equals(each.refundStatus())) total = total.add(decimal(each.refundAmount()));
        }
        String refundFee = total.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        return envelope(type, node -> {
            node.put("code", "10000").put("msg", "Success");
            node.put("trade_no", transaction.tradeNo());
            node.put("out_trade_no", transaction.outTradeNo());
            node.put("out_request_no", refund.outRequestNo());
            node.put("refund_fee", refundFee);
            node.put("refund_amount", refund.refundAmount());
            node.put("fund_change", fundChange);
        });
    }

    private String refundQuery(JsonNode biz) {
        String tradeNo = text(biz, "trade_no");
        String outRequestNo = text(biz, "out_request_no");
        AlipayMockState.Refund stored = state.refund(tradeNo, outRequestNo);
        if (stored == null) return tradeNotExist("alipay_trade_fastpay_refund_query_response", tradeNo);
        AlipayMockState.Refund refund = progress(stored);
        AlipayMockState.Transaction transaction = state.transactionByTradeNo(tradeNo);
        return envelope("alipay_trade_fastpay_refund_query_response", node -> {
            node.put("code", "10000").put("msg", "Success");
            node.put("trade_no", tradeNo);
            if (transaction != null) {
                node.put("out_trade_no", transaction.outTradeNo());
                node.put("total_amount", transaction.totalAmount());
            }
            node.put("out_request_no", outRequestNo);
            // 退款未成功（处理中 / 失败）时支付宝不返回 refund_status，商户据此继续轮询。
            if ("REFUND_SUCCESS".equals(refund.refundStatus())) {
                node.put("refund_status", refund.refundStatus());
                node.put("refund_amount", refund.refundAmount());
            }
        });
    }

    private AlipayMockState.Refund progress(AlipayMockState.Refund refund) {
        if (!"PROCESSING".equals(refund.refundStatus()) || refund.resolveTo() == null) return refund;
        AlipayMockState.Refund next = refund.queriesLeft() > 0
                ? new AlipayMockState.Refund(refund.tradeNo(), refund.outRequestNo(), refund.refundAmount(),
                refund.refundStatus(), refund.refundedAt(), refund.resolveTo(), refund.queriesLeft() - 1)
                : new AlipayMockState.Refund(refund.tradeNo(), refund.outRequestNo(), refund.refundAmount(),
                refund.resolveTo(), LocalDateTime.now());
        state.putRefund(next);
        return next;
    }

    private static BigDecimal decimal(String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 账单下载链接查询：失败态由 {@link AlipayBillService#downloadUrl} 按差异配置决定。 */
    private String billDownloadUrl(JsonNode biz) {
        AlipayBillService.UrlResult result = bills.downloadUrl(text(biz, "bill_type"), text(biz, "bill_date"));
        if (result.url() == null) {
            return businessError("alipay_data_dataservice_bill_downloadurl_query_response", result.code(),
                    result.subCode(), result.subMessage());
        }
        return envelope("alipay_data_dataservice_bill_downloadurl_query_response", node -> node
                .put("code", "10000").put("msg", "Success").put("bill_download_url", result.url()));
    }

    private boolean requestSigned(Map<String, String> params) {
        String sign = text(params.get("sign"));
        return sign != null && signer.verify(params, sign, text(params.get("sign_type")));
    }

    private String businessError(String responseType, String code, String subCode, String subMsg) {
        return envelope(responseType, node -> node
                .put("code", code).put("msg", "Business Failed")
                .put("sub_code", subCode).put("sub_msg", subMsg));
    }

    private String tradeNotExist(String responseType, String reference) {
        return businessError(responseType, "40004", "ACQ.TRADE_NOT_EXIST",
                "交易不存在: " + (reference == null ? "" : reference));
    }

    private String envelope(String responseType, Consumer<ObjectNode> filler) {
        ObjectNode inner = NODES.objectNode();
        filler.accept(inner);
        String json = mapper.writeValueAsString(inner);
        return "{\"" + responseType + "\":" + json + ",\"sign\":\"" + signer.sign(json) + "\"}";
    }

    private JsonNode bizContent(Map<String, String> params) {
        String value = text(params.get("biz_content"));
        if (value == null) return NODES.objectNode();
        try {
            JsonNode node = mapper.readTree(value);
            return node == null || node.isNull() ? NODES.objectNode() : node;
        } catch (RuntimeException exception) {
            return NODES.objectNode();
        }
    }

    /** 日志参考号：优先 out_trade_no，其次 trade_no / out_request_no。 */
    private static String reference(JsonNode biz) {
        String value = text(biz, "out_trade_no");
        if (value == null) value = text(biz, "trade_no");
        if (value == null) value = text(biz, "out_request_no");
        return value == null ? "" : value;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String text(JsonNode node, String field) {
        return JsonNodes.text(node, field);
    }
}
