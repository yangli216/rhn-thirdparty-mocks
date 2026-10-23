package com.rhn.mock.protocol.wxpay;

import com.rhn.mock.core.bill.BillRow;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 微信对账单：{@code pay/downloadbill}（bill_type=ALL）。账单基于 Mock 已有交易 / 退款再叠加差异配置；
 * CSV 对齐 RHN {@code WechatStatementParser}：每个单元格以反引号开头，明细后跟“总交易单数…”汇总表头与汇总行。
 */
@Service
public class WxpayBillService {
    private static final DateTimeFormatter BILL_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TIME_END = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String HEADER = "交易时间,公众账号ID,商户号,特约商户号,设备号,微信订单号,商户订单号,用户标识,交易类型,交易状态,"
            + "付款银行,货币种类,应结订单金额,代金券金额,微信退款单号,商户退款单号,退款金额,充值券退款金额,退款类型,退款状态,"
            + "商品名称,商户数据包,手续费,费率,订单金额,申请退款金额,费率备注";

    private final WxpayMockState state;

    public WxpayBillService(WxpayMockState state) {
        this.state = state;
    }

    /** csv 非空表示成功，否则返回 error_code / return_msg。 */
    public record Result(String csv, String errorCode, String message) {
    }

    public Result download(String billDate, String billType, String appId, String mchId) {
        LocalDate date;
        try {
            date = LocalDate.parse(billDate, BILL_DATE);
        } catch (DateTimeParseException | NullPointerException exception) {
            return new Result(null, "20001", "bill_date 必须是 yyyyMMdd");
        }
        if (billType != null && !"ALL".equals(billType)) return new Result(null, "20001", "Mock 只支持 bill_type=ALL");
        switch (state.bill().fetch()) {
            case "NOT_READY" -> {
                return new Result(null, "20003", "Bill Creating");
            }
            case "NOT_FOUND" -> {
                return new Result(null, "20002", "No Bill Exist");
            }
            case "AUTH_FAILED" -> {
                return new Result(null, "SIGNERROR", "sign error");
            }
            case "RATE_LIMITED" -> {
                return new Result(null, "20008", "Frequency Limited");
            }
            case "UNSUPPORTED" -> {
                return new Result(null, "20001", "bill_type not supported");
            }
            default -> {
            }
        }
        if (date.isAfter(LocalDate.now())) return new Result(null, "20003", "Bill Creating");
        // NO_DATA 与“当日无交易”都返回只有汇总行的空账单（总交易单数 0）。
        return new Result(csv(date, appId, mchId, "NO_DATA".equals(state.bill().fetch())), null, null);
    }

    public List<BillRow> rows(LocalDate date) {
        List<BillRow> base = new ArrayList<>();
        for (WxpayMockState.Transaction transaction : state.allTransactions()) {
            LocalDateTime paid = LocalDateTime.parse(transaction.timeEnd(), TIME_END);
            if (!"SUCCESS".equals(transaction.tradeState()) || !paid.toLocalDate().equals(date)) continue;
            base.add(new BillRow("PAYMENT", transaction.outTradeNo(), transaction.transactionId(), null, null,
                    yuan(transaction.totalFee()), paid, null));
        }
        for (WxpayMockState.Refund refund : state.allRefunds()) {
            LocalDateTime at = LocalDateTime.parse(refund.refundTime(), TIME_END);
            if ("REFUNDCLOSE".equals(refund.refundStatus()) || !at.toLocalDate().equals(date)) continue;
            base.add(new BillRow("REFUND", refund.outTradeNo(), refund.transactionId(), refund.outRefundNo(),
                    refund.refundId(), yuan(refund.refundFee()), at, refund.refundStatus()));
        }
        base.sort(Comparator.comparing(BillRow::time));
        return state.bill().apply(base, date);
    }

    public String csv(LocalDate date, String appId, String mchId, boolean empty) {
        List<BillRow> rows = empty ? List.of() : rows(date);
        String eol = "\n";
        StringBuilder csv = new StringBuilder(HEADER).append(eol);
        BigDecimal orderTotal = BigDecimal.ZERO;
        BigDecimal settleTotal = BigDecimal.ZERO;
        BigDecimal refundApplyTotal = BigDecimal.ZERO;
        BigDecimal refundTotal = BigDecimal.ZERO;
        for (BillRow row : rows) {
            String amount = row.amount().setScale(2, RoundingMode.HALF_UP).toPlainString();
            String[] cells = new String[27];
            java.util.Arrays.fill(cells, "");
            cells[0] = row.time().format(TIME);
            cells[1] = appId;
            cells[2] = mchId;
            cells[3] = "0";
            cells[4] = "";
            cells[5] = row.tradeNo();
            cells[6] = row.outTradeNo();
            cells[7] = "oMockWxOpenid000000000000";
            cells[8] = "MICROPAY";
            cells[10] = "CFT";
            cells[11] = "CNY";
            cells[13] = "0.00";
            cells[20] = "Mock商品";
            cells[22] = "0.00";
            cells[23] = "0.60%";
            if (row.refund()) {
                cells[9] = "REFUND";
                cells[12] = "0.00";
                cells[14] = row.refundId();
                cells[15] = row.outRefundNo();
                cells[16] = amount;
                cells[17] = "0.00";
                cells[18] = "ORIGINAL";
                cells[19] = row.refundStatus() == null ? "SUCCESS" : row.refundStatus();
                cells[24] = "0.00";
                cells[25] = amount;
                refundApplyTotal = refundApplyTotal.add(row.amount());
                refundTotal = refundTotal.add(row.amount());
            } else {
                cells[9] = "SUCCESS";
                cells[12] = amount;
                cells[16] = "0.00";
                cells[24] = amount;
                cells[25] = "0.00";
                orderTotal = orderTotal.add(row.amount());
                settleTotal = settleTotal.add(row.amount());
            }
            csv.append(String.join(",", java.util.Arrays.stream(cells).map(cell -> "`" + cell).toList())).append(eol);
        }
        long count = rows.size() + ("CORRUPT".equals(state.bill().fetch()) ? 1 : 0);
        csv.append("总交易单数,应结订单总金额,退款总金额,充值券退款总金额,手续费总金额,订单总金额,申请退款总金额").append(eol)
                .append('`').append(count).append(",`").append(money(settleTotal)).append(",`").append(money(refundTotal))
                .append(",`0.00,`0.00,`").append(money(orderTotal)).append(",`").append(money(refundApplyTotal)).append(eol);
        return csv.toString();
    }

    private static BigDecimal yuan(String fen) {
        return new BigDecimal(fen).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
