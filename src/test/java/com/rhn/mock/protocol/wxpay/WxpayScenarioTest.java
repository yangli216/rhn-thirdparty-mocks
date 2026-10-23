package com.rhn.mock.protocol.wxpay;

import com.rhn.mock.protocol.payment.support.MockAdminHttp;
import com.rhn.mock.protocol.wxpay.support.WxpayTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** 微信付款码支付的中间状态与业务失败：触发值见 docs/payment-mock-scenarios.md。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WxpayScenarioTest {
    private static final String CODE = "134567890123456789";

    @LocalServerPort
    int port;

    WxpayTestClient client;
    MockAdminHttp admin;

    @BeforeEach
    void setUp() {
        client = new WxpayTestClient(port);
        admin = new MockAdminHttp(port);
        client.reset();
    }

    @AfterEach
    void tearDown() {
        client.reset();
    }

    private String state(String outTradeNo) {
        return client.orderQuery(outTradeNo).value("trade_state");
    }

    private String payOk(String outTradeNo, String fee) {
        return client.micropay(outTradeNo, fee, CODE).value("transaction_id");
    }

    // ---------- 处理中 → 终态 ----------

    @Test
    void userpayingIsAResultCodeFailThenAdminAdvancesToSuccess() {
        WxpayTestClient.Result paying = client.micropay("WP-1", "31", CODE);
        assertThat(paying.value("return_code")).isEqualTo("SUCCESS");
        assertThat(paying.value("result_code")).isEqualTo("FAIL");
        assertThat(paying.value("err_code")).isEqualTo("USERPAYING");
        assertThat(paying.signValid()).isTrue();

        for (int i = 0; i < 4; i++) assertThat(state("WP-1")).isEqualTo("USERPAYING");
        // 同一单号重复发起：仍是 USERPAYING，不重复下单
        assertThat(client.micropay("WP-1", "31", CODE).value("err_code")).isEqualTo("USERPAYING");

        assertThat(admin.post("/mock/wxpay/orders/WP-1/advance", "{\"to\":\"SUCCESS\"}").status()).isEqualTo(200);
        assertThat(state("WP-1")).isEqualTo("SUCCESS");
        assertThat(client.micropay("WP-1", "31", CODE).value("err_code")).isEqualTo("ORDERPAID");
    }

    @Test
    void userpayingAutoResolvesAfterConfiguredQueries() {
        admin.put("/mock/config?protocol=wxpay", "{\"pendingQueries\":1}");
        client.micropay("WP-2", "32", CODE);
        assertThat(state("WP-2")).isEqualTo("USERPAYING");
        assertThat(state("WP-2")).isEqualTo("SUCCESS");

        client.micropay("WP-3", "33", CODE);
        state("WP-3");
        assertThat(state("WP-3")).isEqualTo("CLOSED");

        client.micropay("WP-4", "34", CODE);
        state("WP-4");
        assertThat(state("WP-4")).isEqualTo("PAYERROR");
    }

    @Test
    void suffixTriggersSameAsAmountAndLegacySuffixesStillWork() {
        admin.put("/mock/config?protocol=wxpay", "{\"pendingQueries\":0}");
        client.micropay("WP-5-PAYING-SUCCESS", "900", CODE);
        assertThat(state("WP-5-PAYING-SUCCESS")).isEqualTo("SUCCESS");
        client.micropay("WP-6-NOTPAY", "900", CODE);
        assertThat(state("WP-6-NOTPAY")).isEqualTo("NOTPAY");
        assertThat(client.micropay("WP-7-PAYERROR", "900", CODE).value("result_code")).isEqualTo("SUCCESS");
        assertThat(state("WP-7-PAYERROR")).isEqualTo("PAYERROR");
    }

    @Test
    void adminAdvanceRulesAreEnforced() {
        client.micropay("WP-8", "31", CODE);
        assertThat(admin.post("/mock/wxpay/orders/WP-8/advance", "{\"to\":\"PAYERROR\"}").status()).isEqualTo(200);
        assertThat(admin.post("/mock/wxpay/orders/WP-8/advance", "{\"to\":\"SUCCESS\"}").status()).isEqualTo(409);
        assertThat(admin.post("/mock/wxpay/orders/NOPE/advance", "{\"to\":\"SUCCESS\"}").status()).isEqualTo(404);
        assertThat(admin.post("/mock/wxpay/orders/WP-8/advance", "{\"to\":\"X\"}").status()).isEqualTo(400);
    }

    // ---------- 业务失败 / 结果未知 ----------

    @Test
    void businessFailuresCreateNoOrder() {
        WxpayTestClient.Result nofunds = client.micropay("WP-9", "41", CODE);
        assertThat(nofunds.value("result_code")).isEqualTo("FAIL");
        assertThat(nofunds.value("err_code")).isEqualTo("NOTENOUGH");
        assertThat(client.micropay("WP-10", "42", CODE).value("err_code")).isEqualTo("AUTH_CODE_INVALID");
        assertThat(admin.get("/mock/wxpay/orders").json()).isEmpty();
        // 订单不存在在真实接口是 result_code=FAIL / ORDERNOTEXIST；此 mock 沿用 return_code=FAIL，两者对 WxJava 都是 WxPayException
        assertThat(client.orderQuery("WP-9").value("return_code")).isEqualTo("FAIL");
    }

    @Test
    void unknownResultErrorCodesAreRecoverableByQuery() {
        WxpayTestClient.Result paid = client.micropay("WP-11", "44", CODE);
        assertThat(paid.value("err_code")).isEqualTo("SYSTEMERROR");
        assertThat(paid.signValid()).isTrue();
        assertThat(state("WP-11")).isEqualTo("SUCCESS");

        assertThat(client.micropay("WP-12", "45", CODE).value("err_code")).isEqualTo("BANKERROR");
        assertThat(client.orderQuery("WP-12").value("return_code")).isEqualTo("FAIL");
    }

    // ---------- 退款 ----------

    @Test
    void partialMultipleAndOverRefundsAreChecked() {
        String tx = payOk("WP-13", "1000");
        WxpayTestClient.Result first = client.refund(tx, "WP-13-R1", "300", "1000");
        assertThat(first.value("result_code")).isEqualTo("SUCCESS");
        assertThat(first.value("refund_status")).isEqualTo("SUCCESS");
        assertThat(client.refund(tx, "WP-13-R2", "400", "1000").value("result_code")).isEqualTo("SUCCESS");

        WxpayTestClient.Result over = client.refund(tx, "WP-13-R3", "301", "1000");
        assertThat(over.value("result_code")).isEqualTo("FAIL");
        assertThat(over.value("err_code")).isEqualTo("REFUND_FEE_INVALID");
        assertThat(client.refund(tx, "WP-13-R4", "100", "999").value("err_code")).isEqualTo("REFUND_FEE_MISMATCH");

        // 按订单号查询返回该单全部退款（flat refund_count / *_i）
        WxpayTestClient.Result all = client.postRaw("/pay/refundquery",
                client.signed(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "appid", WxpayTestClient.APP_ID, "mch_id", WxpayTestClient.MCH_ID,
                        "nonce_str", "n1", "out_trade_no", "WP-13"))));
        assertThat(all.value("refund_count")).isEqualTo("2");
        assertThat(all.value("out_refund_no_0")).isEqualTo("WP-13-R1");
        assertThat(all.value("refund_fee_1")).isEqualTo("400");
    }

    @Test
    void repeatingSameRefundIsIdempotent() {
        String tx = payOk("WP-14", "1000");
        WxpayTestClient.Result first = client.refund(tx, "WP-14-R1", "600", "1000");
        WxpayTestClient.Result again = client.refund(tx, "WP-14-R1", "600", "1000");
        assertThat(again.value("result_code")).isEqualTo("SUCCESS");
        assertThat(again.value("refund_id")).isEqualTo(first.value("refund_id"));
        assertThat(admin.get("/mock/wxpay/refunds").json()).hasSize(1);
        assertThat(client.refund(tx, "WP-14-R1", "700", "1000").value("err_code")).isEqualTo("INVALID_REQUEST");
        assertThat(client.refund(tx, "WP-14-R2", "400", "1000").value("result_code")).isEqualTo("SUCCESS");
    }

    @Test
    void refundOfUnpaidOrderIsRejected() {
        client.micropay("WP-15", "31", CODE);
        String tx = admin.get("/mock/wxpay/orders/WP-15").json().path("transactionId").asString();
        assertThat(client.refund(tx, "WP-15-R1", "10", "31").value("err_code")).isEqualTo("TRADE_STATUS_ERROR");
    }

    @Test
    void processingRefundIsReportedByRefundQueryUntilAdvanced() {
        String tx = payOk("WP-16", "500");
        WxpayTestClient.Result refund = client.refund(tx, "WP-16-R1", "61", "500");
        assertThat(refund.value("result_code")).isEqualTo("SUCCESS");
        assertThat(refund.fields()).doesNotContainKey("refund_status");
        for (int i = 0; i < 3; i++) {
            assertThat(client.refundQuery("WP-16-R1").value("refund_status_0")).isEqualTo("PROCESSING");
        }
        // 处理中的退款已出现在对账单（微信账单有 PROCESSING 状态列）
        assertThat(admin.get("/mock/wxpay/bill/" + java.time.LocalDate.now()).json().toString()).contains("WP-16-R1");

        assertThat(admin.post("/mock/wxpay/refunds/WP-16-R1/advance", "{\"to\":\"SUCCESS\"}").status()).isEqualTo(200);
        assertThat(client.refundQuery("WP-16-R1").value("refund_status_0")).isEqualTo("SUCCESS");
        assertThat(admin.post("/mock/wxpay/refunds/WP-16-R1/advance", "{\"to\":\"CHANGE\"}").status()).isEqualTo(409);
    }

    @Test
    void processingRefundAutoResolvesToSuccessOrRefundClose() {
        admin.put("/mock/config?protocol=wxpay", "{\"pendingQueries\":1}");
        String tx = payOk("WP-17", "500");
        client.refund(tx, "WP-17-R1", "62", "500");
        client.refund(tx, "WP-17-R2", "63", "500");

        assertThat(client.refundQuery("WP-17-R1").value("refund_status_0")).isEqualTo("PROCESSING");
        assertThat(client.refundQuery("WP-17-R1").value("refund_status_0")).isEqualTo("SUCCESS");
        client.refundQuery("WP-17-R2");
        assertThat(client.refundQuery("WP-17-R2").value("refund_status_0")).isEqualTo("REFUNDCLOSE");
        // REFUNDCLOSE 的退款不占额度、不进对账单
        assertThat(client.refund(tx, "WP-17-R3", "438", "500").value("result_code")).isEqualTo("SUCCESS");
        assertThat(admin.get("/mock/wxpay/bill/" + java.time.LocalDate.now()).json().toString()).doesNotContain("WP-17-R2");
    }

    @Test
    void adminCanCloseRefundAndStatusChangeIsAvailable() {
        String tx = payOk("WP-18", "500");
        client.refund(tx, "WP-18-R1", "61", "500");
        admin.post("/mock/wxpay/refunds/WP-18-R1/advance", "{\"to\":\"REFUNDCLOSE\"}");
        assertThat(client.refundQuery("WP-18-R1").value("refund_status_0")).isEqualTo("REFUNDCLOSE");
        client.refund(tx, "WP-18-R2", "61", "500");
        admin.post("/mock/wxpay/refunds/WP-18-R2/advance", "{\"to\":\"CHANGE\"}");
        assertThat(client.refundQuery("WP-18-R2").value("refund_status_0")).isEqualTo("CHANGE");
    }

    @Test
    void rejectedAndUnknownRefundScenarios() {
        String tx = payOk("WP-19", "500");
        WxpayTestClient.Result rejected = client.refund(tx, "WP-19-R1", "65", "500");
        assertThat(rejected.value("result_code")).isEqualTo("FAIL");
        assertThat(rejected.value("err_code")).isEqualTo("NOTENOUGH");
        assertThat(admin.get("/mock/wxpay/refunds").json()).isEmpty();

        assertThat(client.refund(tx, "WP-19-R2", "64", "500").value("err_code")).isEqualTo("SYSTEMERROR");
        assertThat(client.refundQuery("WP-19-R2").value("refund_status_0")).isEqualTo("SUCCESS");
    }

    @Test
    void refundStateIsReportedByOrderQueryOnlyWhenEnabled() {
        String tx = payOk("WP-20", "500");
        client.refund(tx, "WP-20-R1", "100", "500");
        assertThat(state("WP-20")).isEqualTo("SUCCESS");
        admin.put("/mock/config?protocol=wxpay", "{\"reportRefundState\":true}");
        assertThat(state("WP-20")).isEqualTo("REFUND");
    }

    // ---------- 管理端点 ----------

    @Test
    void adminViewsClearAndReset() {
        String tx = payOk("WP-21", "500");
        client.refund(tx, "WP-21-R1", "100", "500");
        JsonNode order = admin.get("/mock/wxpay/orders/WP-21").json();
        assertThat(order.path("tradeState").asString()).isEqualTo("SUCCESS");
        assertThat(order.path("refunds")).hasSize(1);
        assertThat(admin.get("/mock/wxpay/orders/NOPE").status()).isEqualTo(404);

        assertThat(admin.delete("/mock/wxpay/orders").status()).isEqualTo(200);
        assertThat(admin.get("/mock/wxpay/orders").json()).isEmpty();
        assertThat(admin.get("/mock/wxpay/refunds").json()).isEmpty();
    }

    @Test
    void scenarioExchangesAreRecordedInExchangeLog() {
        client.micropay("WP-22", "31", CODE);
        JsonNode log = admin.get("/mock/requests?protocol=wxpay&op=/pay/micropay").json();
        assertThat(log).hasSize(1);
        assertThat(log.get(0).path("response").asString()).contains("USERPAYING");
    }
}
