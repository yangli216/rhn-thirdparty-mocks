package com.rhn.mock.protocol.chs;

import com.rhn.mock.protocol.chs.config.ChsMockConfig;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 2206/2207 金额分解：甲类统筹 80%，乙类先行自付 15% 后统筹 70%，丙类全自费；
 * 剩余部分在 acct_used_flag=1 且职工险(310)时优先个账，其余现金。比例可配置，不代表真实政策。
 */
@Component
public class SettlementCalculator {
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    public record FeeLine(String feedetlSn, BigDecimal amount, String level) {
    }

    /** 一条 setlinfo 的资金分解结果（金额均保留两位小数）。 */
    public record Settlement(String setlId, BigDecimal medfeeSumamt, BigDecimal fundPaySumamt, BigDecimal hifpPay,
                             BigDecimal hifobPay, BigDecimal acctPay, BigDecimal psnCashPay, BigDecimal inscpScpAmt,
                             BigDecimal fulamtOwnpayAmt, BigDecimal preselfpayAmt) {

        public Settlement withSetlId(String id) {
            return new Settlement(id, medfeeSumamt, fundPaySumamt, hifpPay, hifobPay, acctPay, psnCashPay,
                    inscpScpAmt, fulamtOwnpayAmt, preselfpayAmt);
        }

        /** TEST-IMBALANCE：故意使 medfee_sumamt 与右侧合计不等。 */
        public Settlement nonBalanced() {
            return new Settlement(setlId, medfeeSumamt, fundPaySumamt, hifpPay, hifobPay, acctPay,
                    psnCashPay.add(new BigDecimal("0.01")), inscpScpAmt, fulamtOwnpayAmt, preselfpayAmt);
        }
    }

    public Settlement calculate(String setlId, BigDecimal total, List<FeeLine> lines, boolean acctUsed,
                                String insutype, BigDecimal balance, ChsMockConfig config) {
        BigDecimal fund = ZERO;
        BigDecimal fulamt = ZERO;
        BigDecimal preself = ZERO;
        for (FeeLine line : lines) {
            BigDecimal amount = line.amount();
            switch (line.level()) {
                case "01" -> fund = fund.add(amount.multiply(config.ipPoolRatio()));
                case "02" -> {
                    BigDecimal pre = scale2(amount.multiply(config.preselfRatio()));
                    preself = preself.add(pre);
                    fund = fund.add(scale2(amount.subtract(pre).multiply(config.ipPoolRatioB())));
                }
                default -> fulamt = fulamt.add(amount);
            }
        }
        fund = scale2(fund);
        if (fund.signum() < 0 || fund.compareTo(total) > 0) fund = scale2(total);
        fulamt = scale2(fulamt);
        preself = scale2(preself);

        BigDecimal self = total.subtract(fund);
        BigDecimal account = ZERO;
        BigDecimal cash = self;
        if (acctUsed && "310".equals(insutype) && balance != null && balance.signum() > 0) {
            account = scale2(balance.min(self).max(ZERO));
            cash = self.subtract(account);
        }
        BigDecimal inscp = scale2(total.subtract(fulamt));
        return new Settlement(setlId, scale2(total), fund, fund, ZERO, scale2(account), scale2(cash), inscp, fulamt, preself);
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
