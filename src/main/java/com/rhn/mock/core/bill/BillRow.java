package com.rhn.mock.core.bill;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 渠道账单中的一行（支付或退款），金额单位元、恒为正数；由 alipay / wxpay 各自渲染成渠道账单格式。
 *
 * @param type         PAYMENT / REFUND
 * @param tradeNo      渠道交易号（支付宝 trade_no / 微信 transaction_id）
 * @param refundId     渠道退款单号（微信 refund_id；支付宝无）
 * @param refundStatus 仅微信退款行使用：SUCCESS / PROCESSING / FAIL / CHANGE
 */
public record BillRow(String type, String outTradeNo, String tradeNo, String outRefundNo, String refundId,
                      BigDecimal amount, LocalDateTime time, String refundStatus) {
    public boolean refund() {
        return "REFUND".equals(type);
    }

    /** 本行在差异配置里的键：支付用 outTradeNo，退款用 outRefundNo。 */
    public String key() {
        return refund() ? outRefundNo : outTradeNo;
    }

    BillRow withAmount(BigDecimal value) {
        return new BillRow(type, outTradeNo, tradeNo, outRefundNo, refundId, value, time, refundStatus);
    }

    BillRow withRefundStatus(String value) {
        return new BillRow(type, outTradeNo, tradeNo, outRefundNo, refundId, amount, time, value);
    }
}
