package com.rhn.mock.protocol.wxpay;

import com.rhn.mock.core.ExchangeLog;
import com.rhn.mock.core.MockHttpResponse;
import com.rhn.mock.core.fault.PaymentScenarios;
import com.rhn.mock.protocol.wxpay.config.WxpayMockProperties;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

/**
 * 微信支付 API v2 网关仿真（付款码/查单/退款/退款查询）。
 *
 * <p>请求与响应均为 {@code <xml>}；响应先按"业务字段 → MD5 加签 → 追加 sign → 序列化"的顺序构造，
 * 因此签名原文就是发出的元素集，与 WxJava {@code BaseWxPayResult.toMap()} 的验签口径天然一致。
 * 业务失败（坏签名、未知单号）仍返回带签名的 {@code return_code=FAIL}，让 SDK 侧抛出可识别的异常。
 */
@Service
public class WxpayGatewayService {
    public static final String MICROPAY = "/pay/micropay";
    public static final String ORDER_QUERY = "/pay/orderquery";
    public static final String REFUND = "/secapi/pay/refundv2";
    public static final String REFUND_QUERY = "/pay/refundquery";
    public static final String DOWNLOAD_BILL = "/pay/downloadbill";

    private static final DateTimeFormatter TIME_END = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String OPENID = "oMockWxOpenid000000000000";
    private static final String CONTENT_TYPE = "text/xml;charset=UTF-8";

    private final WxpayMockProperties properties;
    private final WxpaySigner signer;
    private final WxpayMockState state;
    private final ExchangeLog log;
    private final WxpayBillService bills;

    public WxpayGatewayService(WxpayMockProperties properties, WxpaySigner signer,
                               WxpayMockState state, ExchangeLog log, WxpayBillService bills) {
        this.bills = bills;
        this.properties = properties;
        this.signer = signer;
        this.state = state;
        this.log = log;
    }

    public MockHttpResponse handle(String op, String xml) {
        Map<String, String> request = WxpayXml.parse(xml);
        String reference = reference(request);
        String fault = state.faults().take(opKey(op), reference);
        MockHttpResponse response = faulted(fault) ? MockHttpResponse.raw(200, "", CONTENT_TYPE) : dispatch(op, request);
        if (fault != null) response = applyFault(fault, response);
        String logged = (fault == null ? "" : "[fault=" + fault + "] ") + response.body();
        log.add(new ExchangeLog.Entry(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                WxpayProtocol.ID, op, reference, response.httpStatus(), xml, logged));
        return response;
    }

    private MockHttpResponse dispatch(String op, Map<String, String> request) {
        if (state.requestSignRequired() && !signer.verify(request, text(request.get("sign")))) {
            return failure("签名校验失败");
        }
        synchronized (state) {
            return switch (op) {
                case MICROPAY -> micropay(request);
                case ORDER_QUERY -> orderQuery(request);
                case REFUND -> refund(request);
                case REFUND_QUERY -> refundQuery(request);
                case DOWNLOAD_BILL -> downloadBill(request);
                default -> failure("不支持的接口: " + op);
            };
        }
    }

    /** 请求根本没被处理的故障（应答阶段故障 faultProcessed=true 时业务照常执行，只是应答丢失/损坏）。 */
    private boolean faulted(String fault) {
        return fault != null && !state.faults().faultProcessed() && Set.of("HTTP500", "BADBODY", "TIMEOUT").contains(fault);
    }

    private MockHttpResponse applyFault(String fault, MockHttpResponse normal) {
        return switch (fault) {
            case "HTTP500" -> MockHttpResponse.raw(500, "simulated wxpay http 500", "text/plain;charset=UTF-8");
            case "BADBODY" -> MockHttpResponse.raw(200, "this is not xml <<<", CONTENT_TYPE);
            case "SLOW" -> {
                sleep(state.faults().slowDelayMs());
                yield normal;
            }
            case "TIMEOUT" -> {
                sleep(state.faults().timeoutDelayMs());
                yield normal;
            }
            case "NOSIGN" -> MockHttpResponse.raw(normal.httpStatus(),
                    normal.body().replaceAll("<sign><!\\[CDATA\\[[^\\]]*\\]\\]></sign>", ""), normal.contentType());
            case "BADSIGN" -> MockHttpResponse.raw(normal.httpStatus(), badSign(normal.body()), normal.contentType());
            default -> normal;
        };
    }

    /** 把签名首字符换成另一个十六进制字符：格式合法、值错误。 */
    private static String badSign(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("<sign><!\\[CDATA\\[([0-9A-Fa-f])").matcher(body);
        if (!matcher.find()) return body;
        String flipped = "0".equals(matcher.group(1)) ? "1" : "0";
        return body.substring(0, matcher.start(1)) + flipped + body.substring(matcher.end(1));
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String opKey(String op) {
        return switch (op) {
            case MICROPAY -> "pay";
            case ORDER_QUERY -> "query";
            case REFUND -> "refund";
            case REFUND_QUERY -> "refundquery";
            case DOWNLOAD_BILL -> "bill";
            default -> op;
        };
    }

    /** 日志参考号 / 逐单故障后缀所在：out_trade_no，其次 out_refund_no / transaction_id。 */
    private static String reference(Map<String, String> request) {
        String value = text(request.get("out_trade_no"));
        if (value == null) value = text(request.get("out_refund_no"));
        if (value == null) value = text(request.get("transaction_id"));
        return value == null ? "" : value;
    }

    private MockHttpResponse micropay(Map<String, String> request) {
        String outTradeNo = text(request.get("out_trade_no"));
        String totalFee = text(request.get("total_fee"));
        if (outTradeNo == null || totalFee == null) return failure("缺少 out_trade_no/total_fee");
        WxpayMockState.Transaction existing = state.transactionByOutTradeNo(outTradeNo);
        if (existing != null) {
            // 同一 out_trade_no 重复发起：按既有状态应答，不重复扣款。
            return switch (existing.tradeState()) {
                case "USERPAYING" -> bizFailure(request, "USERPAYING", "需要用户输入支付密码");
                case "SUCCESS" -> bizFailure(request, "ORDERPAID", "商户订单已支付");
                default -> bizFailure(request, "ORDERCLOSED", "订单已关闭");
            };
        }
        String scenario = PaymentScenarios.pay(outTradeNo, PaymentScenarios.fenToYuan(totalFee));
        if (scenario == null) scenario = "";
        switch (scenario) {
            case "NOFUNDS" -> {
                return bizFailure(request, "NOTENOUGH", "余额不足");
            }
            case "BADCODE" -> {
                return bizFailure(request, "AUTH_CODE_INVALID", "授权码无效，请重新扫码");
            }
            case "UNKNOWN_UNPAID" -> {
                return bizFailure(request, "BANKERROR", "银行端超时，请调用被扫订单结果查询API确认");
            }
            default -> {
            }
        }
        String transactionId = state.nextTransactionId();
        String timeEnd = LocalDateTime.now().format(TIME_END);
        String tradeState = switch (scenario) {
            case "PAYING", "PAYING_SUCCESS", "PAYING_CLOSED", "PAYING_FAILED" -> "USERPAYING";
            case "NOTPAY" -> "NOTPAY";
            default -> tradeState(outTradeNo);
        };
        String resolveTo = switch (scenario) {
            case "PAYING_SUCCESS" -> "SUCCESS";
            case "PAYING_CLOSED" -> "CLOSED";
            case "PAYING_FAILED" -> "PAYERROR";
            default -> null;
        };
        state.putTransaction(new WxpayMockState.Transaction(outTradeNo, transactionId, totalFee, tradeState, OPENID, timeEnd,
                resolveTo, resolveTo == null ? 0 : state.faults().pendingQueries()));
        if ("USERPAYING".equals(tradeState)) return bizFailure(request, "USERPAYING", "需要用户输入支付密码");
        if ("UNKNOWN_PAID".equals(scenario)) return bizFailure(request, "SYSTEMERROR", "系统错误，请调用被扫订单结果查询API确认");

        Map<String, String> fields = base(request);
        fields.put("openid", OPENID);
        fields.put("trade_type", "MICROPAY");
        fields.put("bank_type", properties.defaultBankType());
        fields.put("fee_type", "CNY");
        fields.put("total_fee", totalFee);
        fields.put("cash_fee", totalFee);
        fields.put("transaction_id", transactionId);
        fields.put("out_trade_no", outTradeNo);
        fields.put("time_end", timeEnd);
        return success(fields);
    }

    private MockHttpResponse orderQuery(Map<String, String> request) {
        String outTradeNo = text(request.get("out_trade_no"));
        String transactionId = text(request.get("transaction_id"));
        WxpayMockState.Transaction transaction = outTradeNo != null
                ? state.transactionByOutTradeNo(outTradeNo) : state.transactionByTransactionId(transactionId);
        if (transaction == null) return failure("订单不存在: " + (outTradeNo == null ? transactionId : outTradeNo));
        transaction = progress(transaction);
        String reported = transaction.tradeState();
        if ("SUCCESS".equals(reported) && state.reportRefundState() && refundedAny(transaction)) reported = "REFUND";

        Map<String, String> fields = base(request);
        fields.put("openid", transaction.openid());
        fields.put("trade_type", "MICROPAY");
        fields.put("trade_state", reported);
        fields.put("trade_state_desc", tradeStateDesc(reported));
        fields.put("bank_type", properties.defaultBankType());
        fields.put("fee_type", "CNY");
        fields.put("total_fee", transaction.totalFee());
        fields.put("cash_fee", transaction.totalFee());
        fields.put("transaction_id", transaction.transactionId());
        fields.put("out_trade_no", transaction.outTradeNo());
        fields.put("time_end", transaction.timeEnd());
        return success(fields);
    }

    private boolean refundedAny(WxpayMockState.Transaction transaction) {
        return state.refundsOfTransaction(transaction.transactionId()).stream()
                .anyMatch(refund -> !"REFUNDCLOSE".equals(refund.refundStatus()));
    }

    /** 每次查询推进一步：USERPAYING 的订单查满 queriesLeft 次后落到 resolveTo。 */
    private WxpayMockState.Transaction progress(WxpayMockState.Transaction transaction) {
        if (!"USERPAYING".equals(transaction.tradeState()) || transaction.resolveTo() == null) return transaction;
        WxpayMockState.Transaction next = transaction.queriesLeft() > 0
                ? new WxpayMockState.Transaction(transaction.outTradeNo(), transaction.transactionId(), transaction.totalFee(),
                transaction.tradeState(), transaction.openid(), transaction.timeEnd(), transaction.resolveTo(),
                transaction.queriesLeft() - 1)
                : resolved(transaction, transaction.resolveTo());
        state.putTransaction(next);
        return next;
    }

    public static WxpayMockState.Transaction resolved(WxpayMockState.Transaction transaction, String tradeState) {
        return new WxpayMockState.Transaction(transaction.outTradeNo(), transaction.transactionId(), transaction.totalFee(),
                tradeState, transaction.openid(), LocalDateTime.now().format(TIME_END));
    }

    private MockHttpResponse refund(Map<String, String> request) {
        String transactionId = text(request.get("transaction_id"));
        String outRefundNo = text(request.get("out_refund_no"));
        String refundFee = text(request.get("refund_fee"));
        String totalFee = text(request.get("total_fee"));
        WxpayMockState.Transaction transaction = transactionId != null ? state.transactionByTransactionId(transactionId)
                : state.transactionByOutTradeNo(text(request.get("out_trade_no")));
        if (transaction == null) return failure("订单不存在: " + transactionId);
        if (outRefundNo == null || refundFee == null || totalFee == null) return failure("缺少 out_refund_no/refund_fee/total_fee");
        long fee = number(refundFee);
        if (fee <= 0) return bizFailure(request, "PARAM_ERROR", "退款金额不合法");
        if (!"SUCCESS".equals(transaction.tradeState())) {
            return bizFailure(request, "TRADE_STATUS_ERROR", "订单状态不是支付成功，不能退款");
        }
        if (number(totalFee) != number(transaction.totalFee())) {
            return bizFailure(request, "REFUND_FEE_MISMATCH", "订单金额或退款金额与之前请求不一致，请核实后再试");
        }
        WxpayMockState.Refund existing = state.refundByOutRefundNo(outRefundNo);
        if (existing != null) {
            // 幂等：同一 out_refund_no 同参数重复提交返回同一笔退款，不再动资金。
            if (number(existing.refundFee()) != fee || !existing.transactionId().equals(transaction.transactionId())) {
                return bizFailure(request, "INVALID_REQUEST", "同一 out_refund_no 的退款参数与首次不一致");
            }
            return refundResponse(request, existing);
        }
        long reserved = 0;
        for (WxpayMockState.Refund each : state.refundsOfTransaction(transaction.transactionId())) {
            if (!"REFUNDCLOSE".equals(each.refundStatus())) reserved += number(each.refundFee());
        }
        if (reserved + fee > number(transaction.totalFee())) {
            return bizFailure(request, "REFUND_FEE_INVALID", "申请退款金额超过可退金额");
        }
        String scenario = PaymentScenarios.refund(outRefundNo, PaymentScenarios.fenToYuan(refundFee));
        if ("REFUND_REJECTED".equals(scenario)) return bizFailure(request, "NOTENOUGH", "基本账户余额不足，请充值后重新申请退款");
        String status = "SUCCESS";
        String resolveTo = null;
        int left = 0;
        if (scenario != null && scenario.startsWith("REFUND_") && !"REFUND_UNKNOWN".equals(scenario)) {
            status = "PROCESSING";
            resolveTo = switch (scenario) {
                case "REFUND_SUCCESS_LATER" -> "SUCCESS";
                case "REFUND_CLOSED_LATER" -> "REFUNDCLOSE";
                default -> null;
            };
            left = state.faults().pendingQueries();
        }
        WxpayMockState.Refund created = new WxpayMockState.Refund(transaction.transactionId(), transaction.outTradeNo(),
                outRefundNo, state.nextRefundId(), refundFee, totalFee, status, LocalDateTime.now().format(TIME_END),
                resolveTo, left);
        state.putRefund(created);
        if ("REFUND_UNKNOWN".equals(scenario)) return bizFailure(request, "SYSTEMERROR", "系统错误，请调用退款查询API确认");
        return refundResponse(request, created);
    }

    private MockHttpResponse refundResponse(Map<String, String> request, WxpayMockState.Refund refund) {
        Map<String, String> fields = base(request);
        fields.put("transaction_id", refund.transactionId());
        fields.put("out_trade_no", refund.outTradeNo());
        fields.put("out_refund_no", refund.outRefundNo());
        fields.put("refund_id", refund.refundId());
        fields.put("refund_fee", refund.refundFee());
        fields.put("total_fee", refund.totalFee());
        fields.put("cash_refund_fee", refund.refundFee());
        fields.put("fee_type", "CNY");
        // refundv2 的真实应答不含 refund_status，这里只在已成功时回显（既有契约）；处理中不给，调用方必须查询。
        if ("SUCCESS".equals(refund.refundStatus())) fields.put("refund_status", "SUCCESS");
        return success(fields);
    }

    private MockHttpResponse refundQuery(Map<String, String> request) {
        String outRefundNo = text(request.get("out_refund_no"));
        List<WxpayMockState.Refund> refunds;
        if (outRefundNo != null) {
            WxpayMockState.Refund refund = state.refundByOutRefundNo(outRefundNo);
            if (refund == null) return failure("退款单不存在: " + outRefundNo);
            refunds = List.of(refund);
        } else {
            // 真实接口也允许按 out_trade_no / transaction_id 查该订单下全部退款（多次退款）。
            WxpayMockState.Transaction transaction = text(request.get("out_trade_no")) != null
                    ? state.transactionByOutTradeNo(text(request.get("out_trade_no")))
                    : state.transactionByTransactionId(text(request.get("transaction_id")));
            if (transaction == null) return failure("订单不存在");
            refunds = state.refundsOfTransaction(transaction.transactionId());
            if (refunds.isEmpty()) return failure("退款单不存在");
        }
        refunds = refunds.stream().map(this::progress).toList();

        WxpayMockState.Refund first = refunds.getFirst();
        Map<String, String> fields = base(request);
        fields.put("transaction_id", first.transactionId());
        fields.put("out_trade_no", first.outTradeNo());
        fields.put("total_fee", first.totalFee());
        fields.put("fee_type", "CNY");
        // WxJava 的 composeRefundRecords() 按 refund_count 循环读取 out_refund_no_<i> 等**扁平编号元素**，
        // 不使用 <refund_records><item>。
        fields.put("refund_count", String.valueOf(refunds.size()));
        for (int index = 0; index < refunds.size(); index++) {
            WxpayMockState.Refund refund = refunds.get(index);
            fields.put("out_refund_no_" + index, refund.outRefundNo());
            fields.put("refund_id_" + index, refund.refundId());
            fields.put("refund_fee_" + index, refund.refundFee());
            fields.put("refund_status_" + index, refund.refundStatus());
        }
        return success(fields);
    }

    private WxpayMockState.Refund progress(WxpayMockState.Refund refund) {
        if (!"PROCESSING".equals(refund.refundStatus()) || refund.resolveTo() == null) return refund;
        WxpayMockState.Refund next = refund.queriesLeft() > 0
                ? new WxpayMockState.Refund(refund.transactionId(), refund.outTradeNo(), refund.outRefundNo(), refund.refundId(),
                refund.refundFee(), refund.totalFee(), refund.refundStatus(), refund.refundTime(), refund.resolveTo(),
                refund.queriesLeft() - 1)
                : new WxpayMockState.Refund(refund.transactionId(), refund.outTradeNo(), refund.outRefundNo(), refund.refundId(),
                refund.refundFee(), refund.totalFee(), refund.resolveTo(), LocalDateTime.now().format(TIME_END));
        state.putRefund(next);
        return next;
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    /** 业务失败：return_code=SUCCESS、result_code=FAIL、带 err_code（USERPAYING / NOTENOUGH / SYSTEMERROR…），签名正常。 */
    private MockHttpResponse bizFailure(Map<String, String> request, String errCode, String description) {
        Map<String, String> fields = base(request);
        fields.put("result_code", "FAIL");
        fields.put("err_code", errCode);
        fields.put("err_code_des", description);
        return success(fields);
    }

    /** downloadbill：成功返回 CSV 文本（不是 XML）；失败返回带 error_code 的 XML，供适配器区分未就绪 / 不存在 / 限频等。 */
    private MockHttpResponse downloadBill(Map<String, String> request) {
        WxpayBillService.Result result = bills.download(text(request.get("bill_date")), text(request.get("bill_type")),
                valueOr(request.get("appid"), properties.appId()), valueOr(request.get("mch_id"), properties.mchId()));
        if (result.csv() != null) return MockHttpResponse.raw(200, result.csv(), "text/plain;charset=UTF-8");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("return_code", "FAIL");
        fields.put("return_msg", result.message());
        fields.put("error_code", result.errorCode());
        return xml(WxpayXml.build(fields));
    }

    /** 公共成功字段：回显请求的 appid/mch_id，缺省回填配置值。 */
    private Map<String, String> base(Map<String, String> request) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("return_code", "SUCCESS");
        fields.put("return_msg", "OK");
        fields.put("result_code", "SUCCESS");
        fields.put("appid", valueOr(request.get("appid"), properties.appId()));
        fields.put("mch_id", valueOr(request.get("mch_id"), properties.mchId()));
        fields.put("nonce_str", UUID.randomUUID().toString().replace("-", ""));
        return fields;
    }

    private MockHttpResponse success(Map<String, String> fields) {
        return xml(withSign(fields));
    }

    private MockHttpResponse failure(String message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("return_code", "FAIL");
        fields.put("return_msg", message);
        fields.put("appid", properties.appId());
        fields.put("mch_id", properties.mchId());
        fields.put("nonce_str", UUID.randomUUID().toString().replace("-", ""));
        return xml(withSign(fields));
    }

    /** 追加 sign 后序列化；签名必须覆盖 sign 之外的全部字段。 */
    private String withSign(Map<String, String> fields) {
        fields.put("sign", signer.sign(fields));
        return WxpayXml.build(fields);
    }

    private MockHttpResponse xml(String body) {
        return MockHttpResponse.raw(200, body, CONTENT_TYPE);
    }

    /** 单号后缀决定查单返回的 trade_state，便于测试覆盖适配器的分支处理。 */
    private static String tradeState(String outTradeNo) {
        if (outTradeNo.endsWith("-CLOSED")) return "CLOSED";
        if (outTradeNo.endsWith("-REVOKED")) return "REVOKED";
        if (outTradeNo.endsWith("-PAYERROR")) return "PAYERROR";
        return "SUCCESS";
    }

    private static String tradeStateDesc(String tradeState) {
        return switch (tradeState) {
            case "SUCCESS" -> "支付成功";
            case "USERPAYING" -> "用户支付中";
            case "NOTPAY" -> "未支付";
            case "REFUND" -> "转入退款";
            case "CLOSED" -> "订单已关闭";
            case "REVOKED" -> "订单已撤销";
            case "PAYERROR" -> "支付失败";
            default -> "未知状态";
        };
    }

    private static String valueOr(String value, String fallback) {
        String trimmed = text(value);
        return trimmed == null ? fallback : trimmed;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
