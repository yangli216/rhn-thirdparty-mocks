package com.rhn.mock.protocol.alipay;

import com.rhn.mock.core.bill.BillRow;
import com.rhn.mock.protocol.alipay.config.AlipayMockProperties;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 支付宝对账单：下载链接查询（{@code alipay.data.dataservice.bill.downloadurl.query}）与账单文件生成。
 * 账单基于 Mock 已有的真实交易 / 退款，再叠加 {@link com.rhn.mock.core.bill.BillAnomalies} 构造的差异；
 * 文件格式对齐 RHN {@code AlipayStatementParser}：GBK 的“业务明细”CSV 装在 zip 里，
 * 头部 {@code #起始日期} 元数据、尾部 {@code #交易合计 / #退款合计} 汇总。
 */
@Service
public class AlipayBillService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Charset GBK = Charset.forName("GB18030");

    private final AlipayMockState state;
    private final AlipayMockProperties properties;

    public AlipayBillService(AlipayMockState state, AlipayMockProperties properties) {
        this.state = state;
        this.properties = properties;
    }

    /** 查询结果：url 非空表示成功，否则为 {@code code/sub_code/sub_msg}。 */
    public record UrlResult(String url, String code, String subCode, String subMessage) {
        static UrlResult ok(String url) {
            return new UrlResult(url, "10000", null, null);
        }

        static UrlResult fail(String subCode, String message) {
            return new UrlResult(null, "40004", subCode, message);
        }
    }

    public UrlResult downloadUrl(String billType, String billDate) {
        if (!"trade".equals(billType)) return UrlResult.fail("TYPE_NOT_SUPPORTED", "Mock 只支持 bill_type=trade");
        LocalDate date;
        try {
            date = LocalDate.parse(billDate);
        } catch (DateTimeParseException | NullPointerException exception) {
            return UrlResult.fail("isv.invalid-parameter", "bill_date 必须是 yyyy-MM-dd");
        }
        switch (state.bill().fetch()) {
            case "NOT_READY" -> {
                return UrlResult.fail("BILL_NOT_READY", "账单尚未生成（模拟）");
            }
            case "NOT_FOUND" -> {
                return UrlResult.fail("BILL_NOT_EXIST", "账单不存在（模拟）");
            }
            case "NO_DATA" -> {
                return UrlResult.fail("NO_BILL_DATA", "该日无账单数据（模拟）");
            }
            case "AUTH_FAILED" -> {
                return UrlResult.fail("isv.insufficient-isv-permissions", "无账单下载权限（模拟）");
            }
            case "RATE_LIMITED" -> {
                return UrlResult.fail("USER_RATE_LIMIT", "调用过于频繁（模拟）");
            }
            case "UNSUPPORTED" -> {
                return UrlResult.fail("TYPE_NOT_SUPPORTED", "账单类型不支持（模拟）");
            }
            default -> {
            }
        }
        if (date.isAfter(LocalDate.now())) return UrlResult.fail("BILL_NOT_READY", "账单日尚未到达");
        if (rows(date).isEmpty()) return UrlResult.fail("NO_BILL_DATA", "该日没有交易");
        return UrlResult.ok(properties.billDownloadBaseUrl() + "/" + date);
    }

    /** 账单日内的最终账单行（真实交易 + 差异）。 */
    public List<BillRow> rows(LocalDate date) {
        List<BillRow> base = new ArrayList<>();
        for (AlipayMockState.Transaction transaction : state.allTransactions()) {
            if (!transaction.paid() || !transaction.paidAt().toLocalDate().equals(date)) continue;
            base.add(new BillRow("PAYMENT", transaction.outTradeNo(), transaction.tradeNo(), null, null,
                    new BigDecimal(transaction.totalAmount()), transaction.paidAt(), null));
        }
        for (AlipayMockState.Refund refund : state.allRefunds()) {
            if (!"REFUND_SUCCESS".equals(refund.refundStatus()) || !refund.refundedAt().toLocalDate().equals(date)) continue;
            AlipayMockState.Transaction original = state.transactionByTradeNo(refund.tradeNo());
            base.add(new BillRow("REFUND", original == null ? null : original.outTradeNo(), refund.tradeNo(),
                    refund.outRequestNo(), null, new BigDecimal(refund.refundAmount()), refund.refundedAt(), null));
        }
        base.sort(java.util.Comparator.comparing(BillRow::time));
        return state.bill().apply(base, date);
    }

    /** 业务明细 CSV 文本（UTF-16 字符串，落盘时按 GBK 编码）。 */
    public String csv(LocalDate date) {
        List<BillRow> rows = rows(date);
        String eol = "\r\n";
        StringBuilder csv = new StringBuilder();
        csv.append("#支付宝业务明细查询").append(eol)
                .append("#账号：[").append(properties.sellerId()).append(']').append(eol)
                .append("#起始日期：[").append(date).append(" 00:00:00]    终止日期：[").append(date.plusDays(1)).append(" 00:00:00]").append(eol)
                .append("#---------------------------------业务明细列表----------------------------------------").append(eol)
                .append("支付宝交易号,商户订单号,业务类型,商品名称,创建时间,完成时间,订单金额（元）,商家实收（元）,退款批次号,服务费（元）").append(eol);
        long payments = 0;
        long refunds = 0;
        BigDecimal paymentSum = BigDecimal.ZERO;
        BigDecimal refundSum = BigDecimal.ZERO;
        for (BillRow row : rows) {
            String amount = row.amount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
            String done = row.time().format(TIME);
            if (row.refund()) {
                refunds++;
                refundSum = refundSum.add(row.amount());
                csv.append(row.tradeNo()).append(',').append(row.outTradeNo()).append(",退款,Mock退款,").append(done).append(',')
                        .append(done).append(",-").append(amount).append(",-").append(amount).append(',')
                        .append(row.outRefundNo()).append(",0.00").append(eol);
            } else {
                payments++;
                paymentSum = paymentSum.add(row.amount());
                csv.append(row.tradeNo()).append(',').append(row.outTradeNo()).append(",交易,Mock商品,").append(done).append(',')
                        .append(done).append(',').append(amount).append(',').append(amount).append(",,0.00").append(eol);
            }
        }
        if ("CORRUPT".equals(state.bill().fetch())) payments++; // 汇总故意与明细对不上，演练解析失败
        csv.append("#---------------------------------业务明细列表结束------------------------------------").append(eol)
                .append("#交易合计：").append(payments).append("笔，合计金额：").append(money(paymentSum)).append("元").append(eol)
                .append("#退款合计：").append(refunds).append("笔，合计金额：").append(money(refundSum)).append("元").append(eol);
        return csv.toString();
    }

    /** 下载内容：GBK 编码的 CSV 放进 zip。 */
    public byte[] zip(LocalDate date) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, GBK)) {
            zip.putNextEntry(new ZipEntry("alipay_trade_" + date.toString().replace("-", "") + "_detail.csv"));
            zip.write(csv(date).getBytes(GBK));
            zip.closeEntry();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return bytes.toByteArray();
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
