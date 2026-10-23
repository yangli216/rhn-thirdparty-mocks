package com.rhn.mock.protocol.wxpay;

import com.rhn.mock.core.bill.BillAnomalies;
import com.rhn.mock.core.fault.PaymentFaultConfig;
import com.rhn.mock.protocol.wxpay.config.WxpayMockProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 微信支付 API v2 Mock 内存态：交易、退款与运行时可切换的请求验签开关，重启即清空。 */
@Component
public class WxpayMockState {
    private final boolean requestSignRequiredDefault;
    private final AtomicBoolean requestSignRequired;
    private final AtomicBoolean reportRefundState = new AtomicBoolean(false);
    private final ConcurrentMap<String, Transaction> byOutTradeNo = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Transaction> byTransactionId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Refund> refundsByOutRefundNo = new ConcurrentHashMap<>();
    private final AtomicLong transactionSequence = new AtomicLong();
    private final AtomicLong refundSequence = new AtomicLong();
    private final BillAnomalies bill = new BillAnomalies();
    private final PaymentFaultConfig faults;

    /**
     * {@code tradeState}：SUCCESS / USERPAYING / NOTPAY / CLOSED / REVOKED / PAYERROR。
     * USERPAYING 且 {@code resolveTo} 非空时，被查询 {@code queriesLeft} 次后自动落到 {@code resolveTo}，否则只能由管理端点推进。
     */
    public record Transaction(String outTradeNo, String transactionId, String totalFee, String tradeState,
                              String openid, String timeEnd, String resolveTo, int queriesLeft) {
        public Transaction(String outTradeNo, String transactionId, String totalFee, String tradeState,
                           String openid, String timeEnd) {
            this(outTradeNo, transactionId, totalFee, tradeState, openid, timeEnd, null, 0);
        }
    }

    /** {@code refundStatus}：SUCCESS / PROCESSING / REFUNDCLOSE / CHANGE；PROCESSING 的自动落地规则同 {@link Transaction}。 */
    public record Refund(String transactionId, String outTradeNo, String outRefundNo, String refundId,
                         String refundFee, String totalFee, String refundStatus, String refundTime,
                         String resolveTo, int queriesLeft) {
        public Refund(String transactionId, String outTradeNo, String outRefundNo, String refundId,
                      String refundFee, String totalFee, String refundStatus, String refundTime) {
            this(transactionId, outTradeNo, outRefundNo, refundId, refundFee, totalFee, refundStatus, refundTime, null, 0);
        }
    }

    public WxpayMockState(WxpayMockProperties properties) {
        this.requestSignRequiredDefault = properties.requireRequestSign();
        this.requestSignRequired = new AtomicBoolean(requestSignRequiredDefault);
        this.faults = new PaymentFaultConfig(properties.slowDelayMs(), properties.timeoutDelayMs(), properties.pendingQueries());
    }

    /** 为 true 时，已发生（非 REFUNDCLOSE）退款的成功订单在 orderquery 里报告 trade_state=REFUND（真实微信行为；默认关）。 */
    public boolean reportRefundState() {
        return reportRefundState.get();
    }

    public void reportRefundState(boolean value) {
        reportRefundState.set(value);
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

    /** 生成响应 transaction_id，形状与微信一致（通道码 + 定长序号）。 */
    public String nextTransactionId() {
        return "4200" + String.format("%024d", transactionSequence.incrementAndGet());
    }

    /** 生成响应 refund_id。 */
    public String nextRefundId() {
        return "5030" + String.format("%024d", refundSequence.incrementAndGet());
    }

    public void putTransaction(Transaction transaction) {
        byOutTradeNo.put(transaction.outTradeNo(), transaction);
        byTransactionId.put(transaction.transactionId(), transaction);
    }

    public Transaction transactionByOutTradeNo(String outTradeNo) {
        return outTradeNo == null ? null : byOutTradeNo.get(outTradeNo);
    }

    public Transaction transactionByTransactionId(String transactionId) {
        return transactionId == null ? null : byTransactionId.get(transactionId);
    }

    public void putRefund(Refund refund) {
        refundsByOutRefundNo.put(refund.outRefundNo(), refund);
    }

    public Refund refundByOutRefundNo(String outRefundNo) {
        return outRefundNo == null ? null : refundsByOutRefundNo.get(outRefundNo);
    }

    public List<Refund> refundsOfTransaction(String transactionId) {
        List<Refund> result = new ArrayList<>();
        for (Refund refund : refundsByOutRefundNo.values()) if (refund.transactionId().equals(transactionId)) result.add(refund);
        result.sort(java.util.Comparator.comparing(Refund::refundId));
        return result;
    }

    /** 清空订单与退款单（保留故障配置与账单差异）。 */
    public void clearOrders() {
        byOutTradeNo.clear();
        byTransactionId.clear();
        refundsByOutRefundNo.clear();
    }

    public BillAnomalies bill() {
        return bill;
    }

    public List<Transaction> allTransactions() {
        return new ArrayList<>(byOutTradeNo.values());
    }

    public List<Refund> allRefunds() {
        return new ArrayList<>(refundsByOutRefundNo.values());
    }

    public int transactions() {
        return byOutTradeNo.size();
    }

    public int refunds() {
        return refundsByOutRefundNo.size();
    }

    public void reset() {
        byOutTradeNo.clear();
        byTransactionId.clear();
        refundsByOutRefundNo.clear();
        transactionSequence.set(0);
        refundSequence.set(0);
        bill.clear();
        faults.reset();
        reportRefundState.set(false);
        requestSignRequired.set(requestSignRequiredDefault);
    }
}
