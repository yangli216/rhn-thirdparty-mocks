package com.rhn.mock.protocol.alipay;

import com.rhn.mock.protocol.alipay.support.AlipayTestClient;
import com.rhn.mock.protocol.payment.support.MockAdminHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** 付款码支付的中间状态与业务失败：触发值见 docs/payment-mock-scenarios.md。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlipayScenarioTest {
    private static final String PAY = "alipay_trade_pay_response";
    private static final String QUERY = "alipay_trade_query_response";
    private static final String REFUND = "alipay_trade_refund_response";
    private static final String REFUND_QUERY = "alipay_trade_fastpay_refund_query_response";
    private static final String CODE = "285612345678901234";

    @LocalServerPort
    int port;

    AlipayTestClient client;
    MockAdminHttp admin;

    @BeforeEach
    void setUp() {
        client = new AlipayTestClient(port);
        admin = new MockAdminHttp(port);
        client.reset();
    }

    @AfterEach
    void tearDown() {
        client.reset();
    }

    private String status(String outTradeNo) {
        return client.query(outTradeNo).inner(QUERY).path("trade_status").asString();
    }

    private String payOk(String outTradeNo, String amount) {
        return client.pay(outTradeNo, amount, CODE).inner(PAY).path("trade_no").asString();
    }

    // ---------- 处理中 → 终态 ----------

    @Test
    void amountTriggersWaitBuyerPayThenAdminAdvancesToSuccess() {
        AlipayTestClient.Result paid = client.pay("AP-1", "0.31", CODE);
        JsonNode inner = paid.inner(PAY);
        assertThat(inner.path("code").asString()).isEqualTo("10003");
        assertThat(inner.path("trade_no").asString()).isNotBlank();
        assertThat(paid.signValid(PAY)).isTrue();

        // 无 resolveTo：多次查询都停在等待买家付款，只能手动推进
        for (int i = 0; i < 4; i++) assertThat(status("AP-1")).isEqualTo("WAIT_BUYER_PAY");

        MockAdminHttp.Reply advanced = admin.post("/mock/alipay/trades/AP-1/advance", "{\"to\":\"SUCCESS\"}");
        assertThat(advanced.status()).isEqualTo(200);
        assertThat(status("AP-1")).isEqualTo("TRADE_SUCCESS");
        // 已成功的订单再发起相同单号：不会重复扣款
        assertThat(client.pay("AP-1", "0.31", CODE).inner(PAY).path("sub_code").asString()).isEqualTo("ACQ.TRADE_HAS_SUCCESS");
    }

    @Test
    void waitBuyerPayAutoResolvesToSuccessAfterConfiguredQueries() {
        admin.put("/mock/config?protocol=alipay", "{\"pendingQueries\":2}");
        client.pay("AP-2", "0.32", CODE);

        assertThat(status("AP-2")).isEqualTo("WAIT_BUYER_PAY");
        assertThat(status("AP-2")).isEqualTo("WAIT_BUYER_PAY");
        assertThat(status("AP-2")).isEqualTo("TRADE_SUCCESS");
        assertThat(status("AP-2")).isEqualTo("TRADE_SUCCESS");
    }

    @Test
    void waitBuyerPayAutoResolvesToClosed() {
        admin.put("/mock/config?protocol=alipay", "{\"pendingQueries\":0}");
        client.pay("AP-3", "0.33", CODE);
        assertThat(status("AP-3")).isEqualTo("TRADE_CLOSED");
        // 关闭的单号不能再支付
        assertThat(client.pay("AP-3", "0.33", CODE).inner(PAY).path("sub_code").asString()).isEqualTo("ACQ.TRADE_HAS_CLOSE");
    }

    @Test
    void suffixTriggersSameAsAmount() {
        admin.put("/mock/config?protocol=alipay", "{\"pendingQueries\":0}");
        client.pay("AP-4-PAYING-SUCCESS", "9.00", CODE);
        assertThat(status("AP-4-PAYING-SUCCESS")).isEqualTo("TRADE_SUCCESS");
        client.pay("AP-5-PAYING", "9.00", CODE);
        assertThat(status("AP-5-PAYING")).isEqualTo("WAIT_BUYER_PAY");
    }

    @Test
    void closedTradeCanBeAdvancedOnlyFromAllowedStates() {
        client.pay("AP-6", "0.31", CODE);
        assertThat(admin.post("/mock/alipay/trades/AP-6/advance", "{\"to\":\"CLOSED\"}").status()).isEqualTo(200);
        assertThat(admin.post("/mock/alipay/trades/AP-6/advance", "{\"to\":\"SUCCESS\"}").status()).isEqualTo(409);
        assertThat(admin.post("/mock/alipay/trades/NOPE/advance", "{\"to\":\"SUCCESS\"}").status()).isEqualTo(404);
        assertThat(admin.post("/mock/alipay/trades/AP-6/advance", "{\"to\":\"WHAT\"}").status()).isEqualTo(400);
    }

    @Test
    void tradeFinishedIsReportedAndRefundableAndInBill() {
        JsonNode pay = client.pay("AP-7", "0.51", CODE).inner(PAY);
        assertThat(pay.path("code").asString()).isEqualTo("10000");
        assertThat(pay.path("trade_status").asString()).isEqualTo("TRADE_FINISHED");
        assertThat(status("AP-7")).isEqualTo("TRADE_FINISHED");
        assertThat(client.refund(pay.path("trade_no").asString(), "AP-7-R1", "0.20").inner(REFUND).path("code").asString())
                .isEqualTo("10000");
        String today = java.time.LocalDate.now().toString();
        assertThat(admin.get("/mock/alipay/bill/" + today).json().path("rows").toString()).contains("AP-7");
    }

    // ---------- 业务失败码 ----------

    @Test
    void businessFailuresCreateNoTradeSoQueryFindsNothing() {
        JsonNode nofunds = client.pay("AP-8", "0.41", CODE).inner(PAY);
        assertThat(nofunds.path("code").asString()).isEqualTo("40004");
        assertThat(nofunds.path("sub_code").asString()).isEqualTo("ACQ.BUYER_BALANCE_NOT_ENOUGH");

        JsonNode badCode = client.pay("AP-9", "0.42", CODE).inner(PAY);
        assertThat(badCode.path("sub_code").asString()).isEqualTo("ACQ.PAYMENT_AUTH_CODE_INVALID");

        assertThat(client.query("AP-8").inner(QUERY).path("sub_code").asString()).isEqualTo("ACQ.TRADE_NOT_EXIST");
        assertThat(client.query("AP-9").inner(QUERY).path("sub_code").asString()).isEqualTo("ACQ.TRADE_NOT_EXIST");
        // 失败后可用同一单号换付款码重试，不被当成重复支付
        assertThat(client.pay("AP-8", "0.40", CODE).inner(PAY).path("code").asString()).isEqualTo("10000");
    }

    @Test
    void duplicatePayWithSameOutTradeNoIsRejectedWithoutNewCharge() {
        String tradeNo = payOk("AP-10", "5.00");
        JsonNode again = client.pay("AP-10", "5.00", CODE).inner(PAY);
        assertThat(again.path("code").asString()).isEqualTo("40004");
        assertThat(again.path("sub_code").asString()).isEqualTo("ACQ.TRADE_HAS_SUCCESS");
        assertThat(client.query("AP-10").inner(QUERY).path("trade_no").asString()).isEqualTo(tradeNo);
        assertThat(admin.get("/mock/alipay/trades").json()).hasSize(1);
    }

    @Test
    void unknownResultIsRecoverableByQueryWhenPaidAndNotFoundWhenUnpaid() {
        AlipayTestClient.Result paid = client.pay("AP-11", "0.44", CODE);
        assertThat(paid.inner(PAY).path("code").asString()).isEqualTo("20000");
        assertThat(paid.inner(PAY).path("sub_code").asString()).isEqualTo("ACQ.SYSTEM_ERROR");
        assertThat(paid.signValid(PAY)).isTrue();
        assertThat(status("AP-11")).isEqualTo("TRADE_SUCCESS");

        assertThat(client.pay("AP-12", "0.45", CODE).inner(PAY).path("code").asString()).isEqualTo("20000");
        assertThat(client.query("AP-12").inner(QUERY).path("sub_code").asString()).isEqualTo("ACQ.TRADE_NOT_EXIST");
    }

    // ---------- 退款 ----------

    @Test
    void partialAndMultipleRefundsAccumulateAndOverrefundIsRejected() {
        String tradeNo = payOk("AP-13", "10.00");
        JsonNode first = client.refund(tradeNo, "AP-13-R1", "3.00").inner(REFUND);
        assertThat(first.path("code").asString()).isEqualTo("10000");
        assertThat(first.path("refund_fee").asString()).isEqualTo("3.00");
        assertThat(first.path("fund_change").asString()).isEqualTo("Y");
        JsonNode second = client.refund(tradeNo, "AP-13-R2", "4.00").inner(REFUND);
        assertThat(second.path("refund_fee").asString()).isEqualTo("7.00");

        JsonNode over = client.refund(tradeNo, "AP-13-R3", "3.01").inner(REFUND);
        assertThat(over.path("code").asString()).isEqualTo("40004");
        assertThat(over.path("sub_code").asString()).isEqualTo("ACQ.REFUND_AMT_NOT_EQUAL_TOTAL");
        assertThat(client.refundQuery(tradeNo, "AP-13-R3").inner(REFUND_QUERY).path("code").asString()).isEqualTo("40004");

        assertThat(client.refund(tradeNo, "AP-13-R4", "3.00").inner(REFUND).path("refund_fee").asString()).isEqualTo("10.00");
        JsonNode view = admin.get("/mock/alipay/trades/AP-13").json();
        assertThat(view.path("refunds")).hasSize(3);
    }

    @Test
    void repeatingSameRefundRequestIsIdempotentAndConflictingAmountIsRejected() {
        String tradeNo = payOk("AP-14", "10.00");
        client.refund(tradeNo, "AP-14-R1", "6.00");
        JsonNode replay = client.refund(tradeNo, "AP-14-R1", "6.00").inner(REFUND);
        assertThat(replay.path("code").asString()).isEqualTo("10000");
        assertThat(replay.path("fund_change").asString()).isEqualTo("N");
        assertThat(replay.path("refund_fee").asString()).isEqualTo("6.00");
        assertThat(admin.get("/mock/alipay/refunds").json()).hasSize(1);

        JsonNode conflict = client.refund(tradeNo, "AP-14-R1", "7.00").inner(REFUND);
        assertThat(conflict.path("sub_code").asString()).isEqualTo("ACQ.INVALID_PARAMETER");
        // 重复退款不会吃掉剩余额度
        assertThat(client.refund(tradeNo, "AP-14-R2", "4.00").inner(REFUND).path("code").asString()).isEqualTo("10000");
    }

    @Test
    void refundOfUnpaidOrUnknownTradeIsRejected() {
        String tradeNo = client.pay("AP-15", "0.31", CODE).inner(PAY).path("trade_no").asString();
        assertThat(client.refund(tradeNo, "AP-15-R1", "0.10").inner(REFUND).path("sub_code").asString())
                .isEqualTo("ACQ.TRADE_STATUS_ERROR");
        assertThat(client.refund("NO-SUCH-TRADE", "X", "1.00").inner(REFUND).path("sub_code").asString())
                .isEqualTo("ACQ.TRADE_NOT_EXIST");
    }

    @Test
    void processingRefundHasNoRefundStatusUntilAdvanced() {
        String tradeNo = payOk("AP-16", "5.00");
        JsonNode refund = client.refund(tradeNo, "AP-16-R1", "0.61").inner(REFUND);
        assertThat(refund.path("code").asString()).isEqualTo("10000");
        assertThat(refund.path("fund_change").asString()).isEqualTo("N");

        for (int i = 0; i < 3; i++) {
            JsonNode query = client.refundQuery(tradeNo, "AP-16-R1").inner(REFUND_QUERY);
            assertThat(query.path("code").asString()).isEqualTo("10000");
            assertThat(query.has("refund_status")).isFalse();
        }
        // 处理中的退款不在对账单里
        assertThat(admin.get("/mock/alipay/bill/" + java.time.LocalDate.now()).json().path("rows").toString())
                .doesNotContain("AP-16-R1");

        assertThat(admin.post("/mock/alipay/refunds/AP-16-R1/advance", "{\"to\":\"SUCCESS\"}").status()).isEqualTo(200);
        JsonNode done = client.refundQuery(tradeNo, "AP-16-R1").inner(REFUND_QUERY);
        assertThat(done.path("refund_status").asString()).isEqualTo("REFUND_SUCCESS");
        assertThat(done.path("refund_amount").asString()).isEqualTo("0.61");
        assertThat(admin.post("/mock/alipay/refunds/AP-16-R1/advance", "{\"to\":\"FAILED\"}").status()).isEqualTo(409);
    }

    @Test
    void processingRefundAutoSucceedsAfterConfiguredQueries() {
        admin.put("/mock/config?protocol=alipay", "{\"pendingQueries\":1}");
        String tradeNo = payOk("AP-17", "5.00");
        client.refund(tradeNo, "AP-17-R1", "0.62");
        assertThat(client.refundQuery(tradeNo, "AP-17-R1").inner(REFUND_QUERY).has("refund_status")).isFalse();
        assertThat(client.refundQuery(tradeNo, "AP-17-R1").inner(REFUND_QUERY).path("refund_status").asString())
                .isEqualTo("REFUND_SUCCESS");
    }

    @Test
    void failedProcessingRefundNeverReportsSuccess() {
        String tradeNo = payOk("AP-18", "5.00");
        client.refund(tradeNo, "AP-18-R1", "0.61");
        admin.post("/mock/alipay/refunds/AP-18-R1/advance", "{\"to\":\"FAILED\"}");
        assertThat(client.refundQuery(tradeNo, "AP-18-R1").inner(REFUND_QUERY).has("refund_status")).isFalse();
        // 失败的退款不占额度
        assertThat(client.refund(tradeNo, "AP-18-R2", "5.00").inner(REFUND).path("code").asString()).isEqualTo("10000");
    }

    @Test
    void rejectedAndUnknownRefundScenarios() {
        String tradeNo = payOk("AP-19", "5.00");
        JsonNode rejected = client.refund(tradeNo, "AP-19-R1", "0.65").inner(REFUND);
        assertThat(rejected.path("sub_code").asString()).isEqualTo("ACQ.SELLER_BALANCE_NOT_ENOUGH");
        assertThat(admin.get("/mock/alipay/refunds").json()).isEmpty();

        JsonNode unknown = client.refund(tradeNo, "AP-19-R2", "0.64").inner(REFUND);
        assertThat(unknown.path("code").asString()).isEqualTo("20000");
        // 退款其实已执行：重放同一 out_request_no 返回成功，退款查询也是成功
        assertThat(client.refund(tradeNo, "AP-19-R2", "0.64").inner(REFUND).path("code").asString()).isEqualTo("10000");
        assertThat(client.refundQuery(tradeNo, "AP-19-R2").inner(REFUND_QUERY).path("refund_status").asString())
                .isEqualTo("REFUND_SUCCESS");
    }

    // ---------- 管理端点 ----------

    @Test
    void adminViewsListAndClearAndResetEverything() {
        String tradeNo = payOk("AP-20", "5.00");
        client.refund(tradeNo, "AP-20-R1", "1.00");
        client.pay("AP-21", "0.31", CODE);

        JsonNode trades = admin.get("/mock/alipay/trades").json();
        assertThat(trades).hasSize(2);
        JsonNode one = admin.get("/mock/alipay/trades/AP-21").json();
        assertThat(one.path("tradeStatus").asString()).isEqualTo("WAIT_BUYER_PAY");
        assertThat(admin.get("/mock/alipay/trades/UNKNOWN").status()).isEqualTo(404);

        assertThat(admin.delete("/mock/alipay/trades").status()).isEqualTo(200);
        assertThat(admin.get("/mock/alipay/trades").json()).isEmpty();
        assertThat(admin.get("/mock/alipay/refunds").json()).isEmpty();

        payOk("AP-22", "1.00");
        admin.post("/mock/reset", "{}");
        assertThat(admin.get("/mock/alipay/trades").json()).isEmpty();
    }

    @Test
    void scenarioExchangesAreRecordedInExchangeLog() {
        client.pay("AP-23", "0.31", CODE);
        client.query("AP-23");
        JsonNode log = admin.get("/mock/requests?protocol=alipay&op=alipay.trade.query").json();
        assertThat(log).hasSize(1);
        assertThat(log.get(0).path("refId").asString()).isEqualTo("AP-23");
        assertThat(log.get(0).path("response").asString()).contains("WAIT_BUYER_PAY");
    }
}
