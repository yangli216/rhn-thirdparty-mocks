package com.rhn.mock.protocol.alipay;

import com.rhn.mock.core.bill.BillAnomalies;
import com.rhn.mock.core.fault.PaymentFaultConfig;
import com.rhn.mock.protocol.alipay.config.AlipayMockProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 支付宝 Mock 内存态：交易、退款与运行时可切换的请求验签开关，重启即清空。 */
@Component
public class AlipayMockState {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final boolean requestSignRequiredDefault;
    private final AtomicBoolean requestSignRequired;
    private final ConcurrentMap<String, Transaction> byOutTradeNo = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Transaction> byTradeNo = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Refund> refunds = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final BillAnomalies bill = new BillAnomalies();
    private final PaymentFaultConfig faults;

    /**
     * {@code tradeStatus}：WAIT_BUYER_PAY / TRADE_SUCCESS / TRADE_FINISHED / TRADE_CLOSED。
     * 处于 WAIT_BUYER_PAY 且 {@code resolveTo} 非空时，被查询 {@code queriesLeft} 次后自动落到 {@code resolveTo}；
     * {@code resolveTo} 为空则只能由管理端点推进。
     */
    public record Transaction(String outTradeNo, String tradeNo, String totalAmount, String currency, String tradeStatus,
                              LocalDateTime paidAt, String resolveTo, int queriesLeft) {
        public Transaction(String outTradeNo, String tradeNo, String totalAmount, String currency, String tradeStatus,
                           LocalDateTime paidAt) {
            this(outTradeNo, tradeNo, totalAmount, currency, tradeStatus, paidAt, null, 0);
        }

        public boolean paid() {
            return "TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus);
        }
    }

    /** {@code refundStatus}：PROCESSING / REFUND_SUCCESS / FAILED；PROCESSING 的自动落地规则同 {@link Transaction}。 */
    public record Refund(String tradeNo, String outRequestNo, String refundAmount, String refundStatus,
                         LocalDateTime refundedAt, String resolveTo, int queriesLeft) {
        public Refund(String tradeNo, String outRequestNo, String refundAmount, String refundStatus,
                      LocalDateTime refundedAt) {
            this(tradeNo, outRequestNo, refundAmount, refundStatus, refundedAt, null, 0);
        }
    }

    public AlipayMockState(AlipayMockProperties properties) {
        this.requestSignRequiredDefault = properties.requireRequestSign();
        this.requestSignRequired = new AtomicBoolean(requestSignRequiredDefault);
        this.faults = new PaymentFaultConfig(properties.slowDelayMs(), properties.timeoutDelayMs(), properties.pendingQueries());
    }

    public PaymentFaultConfig faults() {
        return faults;
    }

    public boolean requestSignRequired() {
        return requestSignRequired.get();
    }

    public void requestSignRequired(boolean value) {
        requestSignRequired.set(value);
    }

    /** 生成响应 trade_no，形状与支付宝一致（日期 + 序号）。 */
    public String nextTradeNo() {
        return LocalDate.now().format(DAY) + "2000" + String.format("%012d", sequence.incrementAndGet());
    }

    public void putTransaction(Transaction transaction) {
        byOutTradeNo.put(transaction.outTradeNo(), transaction);
        byTradeNo.put(transaction.tradeNo(), transaction);
    }

    public Transaction transaction(String outTradeNo) {
        return outTradeNo == null ? null : byOutTradeNo.get(outTradeNo);
    }

    public Transaction transactionByTradeNo(String tradeNo) {
        return tradeNo == null ? null : byTradeNo.get(tradeNo);
    }

    public void putRefund(Refund refund) {
        refunds.put(key(refund.tradeNo(), refund.outRequestNo()), refund);
    }

    public Refund refund(String tradeNo, String outRequestNo) {
        return tradeNo == null || outRequestNo == null ? null : refunds.get(key(tradeNo, outRequestNo));
    }

    public List<Refund> refundsOf(String tradeNo) {
        List<Refund> result = new ArrayList<>();
        for (Refund refund : refunds.values()) if (refund.tradeNo().equals(tradeNo)) result.add(refund);
        result.sort(java.util.Comparator.comparing(Refund::refundedAt));
        return result;
    }

    /** 清空订单与退款单（保留故障配置与账单差异）。 */
    public void clearOrders() {
        byOutTradeNo.clear();
        byTradeNo.clear();
        refunds.clear();
    }

    public BillAnomalies bill() {
        return bill;
    }

    public List<Transaction> allTransactions() {
        return new ArrayList<>(byOutTradeNo.values());
    }

    public List<Refund> allRefunds() {
        return new ArrayList<>(refunds.values());
    }

    public int transactions() {
        return byOutTradeNo.size();
    }

    public int refunds() {
        return refunds.size();
    }

    public void reset() {
        byOutTradeNo.clear();
        byTradeNo.clear();
        refunds.clear();
        sequence.set(0);
        bill.clear();
        faults.reset();
        requestSignRequired.set(requestSignRequiredDefault);
    }

    private static String key(String tradeNo, String outRequestNo) {
        return tradeNo + "/" + outRequestNo;
    }
}
