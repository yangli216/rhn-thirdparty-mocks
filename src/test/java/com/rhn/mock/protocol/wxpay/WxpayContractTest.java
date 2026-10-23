package com.rhn.mock.protocol.wxpay;

import com.rhn.mock.protocol.wxpay.support.WxpayTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/** 微信支付 API v2 契约：4 个路径的响应字段、响应签名可用 apiKey 验通、坏签名/未知单号被拒。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WxpayContractTest {
    @LocalServerPort
    int port;

    WxpayTestClient client;

    @BeforeEach
    void setUp() {
        client = new WxpayTestClient(port);
        client.reset();
    }

    @Test
    void micropayReturnsSignedTradeAndStoresTransaction() {
        WxpayTestClient.Result result = client.micropay("MOCK-WX-1", "1234", "134567890123456789");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.value("return_code")).isEqualTo("SUCCESS");
        assertThat(result.value("result_code")).isEqualTo("SUCCESS");
        assertThat(result.value("appid")).isEqualTo(WxpayTestClient.APP_ID);
        assertThat(result.value("mch_id")).isEqualTo(WxpayTestClient.MCH_ID);
        assertThat(result.value("out_trade_no")).isEqualTo("MOCK-WX-1");
        assertThat(result.value("total_fee")).isEqualTo("1234");
        assertThat(result.value("cash_fee")).isEqualTo("1234");
        assertThat(result.value("fee_type")).isEqualTo("CNY");
        assertThat(result.value("trade_type")).isEqualTo("MICROPAY");
        assertThat(result.value("bank_type")).isNotBlank();
        assertThat(result.value("openid")).isNotBlank();
        assertThat(result.value("transaction_id")).isNotBlank();
        assertThat(result.value("time_end")).hasSize(14);
        assertThat(result.value("sub_mch_id")).isNull();
        assertThat(result.signValid()).isTrue();
    }

    @Test
    void orderQueryReportsStoredTradeState() {
        WxpayTestClient.Result paid = client.micropay("MOCK-WX-2", "800", "134567890123456789");
        String transactionId = paid.value("transaction_id");

        WxpayTestClient.Result result = client.orderQuery("MOCK-WX-2");

        assertThat(result.value("return_code")).isEqualTo("SUCCESS");
        assertThat(result.value("result_code")).isEqualTo("SUCCESS");
        assertThat(result.value("trade_state")).isEqualTo("SUCCESS");
        assertThat(result.value("out_trade_no")).isEqualTo("MOCK-WX-2");
        assertThat(result.value("transaction_id")).isEqualTo(transactionId);
        assertThat(result.value("total_fee")).isEqualTo("800");
        assertThat(result.value("fee_type")).isEqualTo("CNY");
        assertThat(result.value("trade_state_desc")).isNotBlank();
        assertThat(result.signValid()).isTrue();
    }

    @Test
    void orderQueryReflectsTradeStateHooks() {
        client.micropay("MOCK-WX-CLOSED", "100", "134567890123456789");
        client.micropay("MOCK-WX-REVOKED", "100", "134567890123456789");
        client.micropay("MOCK-WX-PAYERROR", "100", "134567890123456789");

        assertThat(client.orderQuery("MOCK-WX-CLOSED").value("trade_state")).isEqualTo("CLOSED");
        assertThat(client.orderQuery("MOCK-WX-REVOKED").value("trade_state")).isEqualTo("REVOKED");
        assertThat(client.orderQuery("MOCK-WX-PAYERROR").value("trade_state")).isEqualTo("PAYERROR");
    }

    @Test
    void refundAndRefundQueryShareTheOriginalTrade() {
        WxpayTestClient.Result paid = client.micropay("MOCK-WX-3", "2000", "134567890123456789");
        String transactionId = paid.value("transaction_id");

        WxpayTestClient.Result refund = client.refund(transactionId, "MOCK-WX-3-R", "2000", "2000");
        assertThat(refund.value("return_code")).isEqualTo("SUCCESS");
        assertThat(refund.value("result_code")).isEqualTo("SUCCESS");
        assertThat(refund.value("out_refund_no")).isEqualTo("MOCK-WX-3-R");
        assertThat(refund.value("refund_id")).isNotBlank();
        assertThat(refund.value("refund_fee")).isEqualTo("2000");
        assertThat(refund.value("total_fee")).isEqualTo("2000");
        assertThat(refund.value("refund_status")).isEqualTo("SUCCESS");
        assertThat(refund.value("out_trade_no")).isEqualTo("MOCK-WX-3");
        assertThat(refund.signValid()).isTrue();

        WxpayTestClient.Result query = client.refundQuery("MOCK-WX-3-R");
        assertThat(query.value("return_code")).isEqualTo("SUCCESS");
        assertThat(query.value("transaction_id")).isEqualTo(transactionId);
        assertThat(query.value("out_trade_no")).isEqualTo("MOCK-WX-3");
        assertThat(query.value("refund_count")).isEqualTo("1");
        // WxJava 按 refund_count 循环读取扁平编号元素，而非 <refund_records><item>。
        assertThat(query.value("out_refund_no_0")).isEqualTo("MOCK-WX-3-R");
        assertThat(query.value("refund_id_0")).isEqualTo(refund.value("refund_id"));
        assertThat(query.value("refund_fee_0")).isEqualTo("2000");
        assertThat(query.value("refund_status_0")).isEqualTo("SUCCESS");
        assertThat(query.signValid()).isTrue();
    }

    @Test
    void unknownOrderIsRejectedWithSignedFailure() {
        WxpayTestClient.Result result = client.orderQuery("MOCK-WX-MISSING");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.value("return_code")).isEqualTo("FAIL");
        assertThat(result.value("return_msg")).contains("订单不存在");
        assertThat(result.value("result_code")).isNull();
        assertThat(result.signValid()).isTrue();
    }

    @Test
    void unknownRefundIsRejected() {
        WxpayTestClient.Result result = client.refundQuery("MOCK-WX-NO-REFUND");

        assertThat(result.value("return_code")).isEqualTo("FAIL");
        assertThat(result.value("return_msg")).contains("退款单不存在");
    }

    @Test
    void tamperedSignatureIsRejected() {
        WxpayTestClient.Result result = client.micropayWithBadSignature("MOCK-WX-BAD", "100");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.value("return_code")).isEqualTo("FAIL");
        assertThat(result.value("return_msg")).contains("签名");
        assertThat(result.signValid()).isTrue();
    }

    @Test
    void resetClearsTransactionsAndRestoresSignatureCheck() {
        client.micropay("MOCK-WX-4", "300", "134567890123456789");
        assertThat(client.orderQuery("MOCK-WX-4").value("return_code")).isEqualTo("SUCCESS");

        client.reset();

        assertThat(client.orderQuery("MOCK-WX-4").value("return_code")).isEqualTo("FAIL");
        assertThat(client.micropayWithBadSignature("MOCK-WX-5", "100").value("return_code")).isEqualTo("FAIL");
    }
}
