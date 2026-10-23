package com.rhn.mock.core.fault;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * 支付协议逐单场景的触发口径（alipay / wxpay 共用）：**金额特殊值**（RHN 真实调用里唯一可控的入参，单号与付款码由 RHN 生成/校验）
 * 与**单号后缀**（curl / 测试直接构造）二选一皆可，金额优先于后缀。
 *
 * <p>支付金额（元；微信为分，换算后同表）：
 * 0.31 PAYING（处理中，等管理端点推进）、0.32 PAYING_SUCCESS（查 N 次后成功）、0.33 PAYING_CLOSED、0.34 PAYING_FAILED、
 * 0.41 NOFUNDS、0.42 BADCODE、0.44 UNKNOWN_PAID（结果未知但已扣款）、0.45 UNKNOWN_UNPAID（结果未知且未扣款）、0.51 FINISHED（仅支付宝）。
 * 退款金额：0.61 REFUND_PROCESSING、0.62 REFUND_SUCCESS_LATER、0.63 REFUND_CLOSED_LATER、0.64 REFUND_UNKNOWN、0.65 REFUND_REJECTED。
 */
public final class PaymentScenarios {
    private static final Map<String, String> PAY_AMOUNT = Map.of(
            "0.31", "PAYING", "0.32", "PAYING_SUCCESS", "0.33", "PAYING_CLOSED", "0.34", "PAYING_FAILED",
            "0.41", "NOFUNDS", "0.42", "BADCODE", "0.44", "UNKNOWN_PAID", "0.45", "UNKNOWN_UNPAID", "0.51", "FINISHED");
    private static final Map<String, String> REFUND_AMOUNT = Map.of(
            "0.61", "REFUND_PROCESSING", "0.62", "REFUND_SUCCESS_LATER", "0.63", "REFUND_CLOSED_LATER",
            "0.64", "REFUND_UNKNOWN", "0.65", "REFUND_REJECTED");
    /** 后缀按长度降序匹配，避免 -PAYING 吞掉 -PAYING-SUCCESS。 */
    private static final String[][] PAY_SUFFIX = {
            {"-PAYING-SUCCESS", "PAYING_SUCCESS"}, {"-PAYING-CLOSED", "PAYING_CLOSED"}, {"-PAYING-FAILED", "PAYING_FAILED"},
            {"-UNKNOWN-NOPAY", "UNKNOWN_UNPAID"}, {"-UNKNOWN", "UNKNOWN_PAID"}, {"-PAYING", "PAYING"},
            {"-NOFUNDS", "NOFUNDS"}, {"-BADCODE", "BADCODE"}, {"-FINISHED", "FINISHED"}, {"-NOTPAY", "NOTPAY"}};
    private static final String[][] REFUND_SUFFIX = {
            {"-RF-PROC-SUCCESS", "REFUND_SUCCESS_LATER"}, {"-RF-PROC-CLOSED", "REFUND_CLOSED_LATER"},
            {"-RF-PROC", "REFUND_PROCESSING"}, {"-RF-UNKNOWN", "REFUND_UNKNOWN"}, {"-RF-REJECT", "REFUND_REJECTED"}};
    private static final String[] FAULT_SUFFIX = {"HTTP500", "BADBODY", "SLOW", "TIMEOUT", "BADSIGN", "NOSIGN"};

    private PaymentScenarios() {
    }

    /** 支付场景；{@code yuan} 为元（如 {@code 0.31}），没有命中返回 null。 */
    public static String pay(String reference, String yuan) {
        String byAmount = PAY_AMOUNT.get(normalize(yuan));
        return byAmount != null ? byAmount : bySuffix(PAY_SUFFIX, reference);
    }

    public static String refund(String reference, String yuan) {
        String byAmount = REFUND_AMOUNT.get(normalize(yuan));
        return byAmount != null ? byAmount : bySuffix(REFUND_SUFFIX, reference);
    }

    /** 逐单故障：单号以 {@code -HTTP500 / -BADBODY / -SLOW / -TIMEOUT / -BADSIGN / -NOSIGN} 结尾。 */
    public static String fault(String reference) {
        if (reference == null) return null;
        String upper = reference.toUpperCase(Locale.ROOT);
        for (String fault : FAULT_SUFFIX) if (upper.endsWith("-" + fault)) return fault;
        return null;
    }

    /** 微信分 → 元。 */
    public static String fenToYuan(String fen) {
        try {
            return new BigDecimal(fen).movePointLeft(2).toPlainString();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String bySuffix(String[][] table, String reference) {
        if (reference == null) return null;
        String upper = reference.toUpperCase(Locale.ROOT);
        for (String[] entry : table) if (upper.endsWith(entry[0])) return entry[1];
        return null;
    }

    private static String normalize(String yuan) {
        if (yuan == null) return null;
        try {
            return new BigDecimal(yuan.trim()).setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
