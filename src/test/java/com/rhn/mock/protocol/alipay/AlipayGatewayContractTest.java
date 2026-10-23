package com.rhn.mock.protocol.alipay;

import com.rhn.mock.protocol.alipay.support.AlipayTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** 支付宝协议契约：4 个 method 的响应字段、响应签名逐字节一致、坏签名/非法 method 被拒。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlipayGatewayContractTest {
    private static final String PAY = "alipay_trade_pay_response";
    private static final String QUERY = "alipay_trade_query_response";
    private static final String REFUND = "alipay_trade_refund_response";
    private static final String REFUND_QUERY = "alipay_trade_fastpay_refund_query_response";
    private static final String ERROR = "error_response";

    @LocalServerPort
    int port;

    AlipayTestClient client;

    @BeforeEach
    void setUp() {
        client = new AlipayTestClient(port);
        client.reset();
    }

    @AfterEach
    void tearDown() {
        client.reset();
    }

    @Test
    void payReturnsSignedTradeAndStoresTransaction() {
        AlipayTestClient.Result result = client.pay("MOCK-ORDER-1", "12.34", "285612345678901234");

        assertThat(result.status()).isEqualTo(200);
        JsonNode pay = result.inner(PAY);
        assertThat(pay.path("code").asString()).isEqualTo("10000");
        assertThat(pay.path("out_trade_no").asString()).isEqualTo("MOCK-ORDER-1");
        assertThat(pay.path("trade_no").asString()).isNotBlank();
        assertThat(pay.path("total_amount").asString()).isEqualTo("12.34");
        assertThat(pay.path("trans_currency").asString()).isEqualTo("CNY");
        assertThat(pay.path("trade_status").asString()).isEqualTo("TRADE_SUCCESS");
        assertThat(result.signValid(PAY)).isTrue();
    }

    @Test
    void queryReportsStoredTradeStatus() {
        AlipayTestClient.Result paid = client.pay("MOCK-ORDER-2", "8.00", "285612345678901234");
        String tradeNo = paid.inner(PAY).path("trade_no").asString();

        AlipayTestClient.Result result = client.query("MOCK-ORDER-2");

        JsonNode query = result.inner(QUERY);
        assertThat(query.path("code").asString()).isEqualTo("10000");
        assertThat(query.path("trade_no").asString()).isEqualTo(tradeNo);
        assertThat(query.path("trade_status").asString()).isEqualTo("TRADE_SUCCESS");
        assertThat(query.path("total_amount").asString()).isEqualTo("8.00");
        assertThat(result.signValid(QUERY)).isTrue();
    }

    @Test
    void closedOutTradeNoIsReportedAsTradeClosed() {
        client.pay("MOCK-ORDER-CLOSED", "5.00", "285612345678901234");

        JsonNode query = client.query("MOCK-ORDER-CLOSED").inner(QUERY);

        assertThat(query.path("code").asString()).isEqualTo("10000");
        assertThat(query.path("trade_status").asString()).isEqualTo("TRADE_CLOSED");
    }

    @Test
    void unknownTradeIsRejectedWithoutFailingTheTransport() {
        AlipayTestClient.Result result = client.query("MOCK-ORDER-MISSING");

        assertThat(result.status()).isEqualTo(200);
        JsonNode query = result.inner(QUERY);
        assertThat(query.path("code").asString()).isEqualTo("40004");
        assertThat(query.path("sub_code").asString()).isEqualTo("ACQ.TRADE_NOT_EXIST");
        assertThat(result.signValid(QUERY)).isTrue();
    }

    @Test
    void refundAndRefundQueryShareTheOriginalTrade() {
        AlipayTestClient.Result paid = client.pay("MOCK-ORDER-3", "20.00", "285612345678901234");
        String tradeNo = paid.inner(PAY).path("trade_no").asString();

        AlipayTestClient.Result refund = client.refund(tradeNo, "MOCK-ORDER-3-R", "20.00");
        JsonNode refundBody = refund.inner(REFUND);
        assertThat(refundBody.path("code").asString()).isEqualTo("10000");
        assertThat(refundBody.path("trade_no").asString()).isEqualTo(tradeNo);
        assertThat(refundBody.path("out_request_no").asString()).isEqualTo("MOCK-ORDER-3-R");
        assertThat(refundBody.path("refund_amount").asString()).isEqualTo("20.00");
        assertThat(refund.signValid(REFUND)).isTrue();

        AlipayTestClient.Result refoundQuery = client.refundQuery(tradeNo, "MOCK-ORDER-3-R");
        JsonNode refundQuery = refoundQuery.inner(REFUND_QUERY);
        assertThat(refundQuery.path("code").asString()).isEqualTo("10000");
        assertThat(refundQuery.path("trade_no").asString()).isEqualTo(tradeNo);
        assertThat(refundQuery.path("out_request_no").asString()).isEqualTo("MOCK-ORDER-3-R");
        assertThat(refundQuery.path("refund_status").asString()).isEqualTo("REFUND_SUCCESS");
        assertThat(refundQuery.path("refund_amount").asString()).isEqualTo("20.00");
        assertThat(refoundQuery.signValid(REFUND_QUERY)).isTrue();
    }

    @Test
    void unknownRefundIsRejected() {
        JsonNode refund = client.refundQuery("202601012000000000000001", "NOT-EXIST").inner(REFUND_QUERY);

        assertThat(refund.path("code").asString()).isEqualTo("40004");
        assertThat(refund.path("sub_code").asString()).isEqualTo("ACQ.TRADE_NOT_EXIST");
    }

    @Test
    void payScenarioHooksDrivePendingAndFailure() {
        JsonNode paying = client.pay("MOCK-ORDER-4", "1.00", "SIM_PAYING_285612345678901234").inner(PAY);
        assertThat(paying.path("code").asString()).isEqualTo("10003");

        JsonNode failing = client.pay("MOCK-ORDER-5", "1.00", "SIM_FAIL_285612345678901234").inner(PAY);
        assertThat(failing.path("code").asString()).isEqualTo("40004");
    }

    @Test
    void unknownMethodIsRejected() {
        AlipayTestClient.Result result = client.call("alipay.trade.cancel", client.newBiz());

        JsonNode error = result.inner(ERROR);
        assertThat(error.path("code").asString()).isEqualTo("40004");
        assertThat(error.path("sub_code").asString()).isEqualTo("isv.invalid-method");
        assertThat(result.signValid(ERROR)).isTrue();
    }

    @Test
    void tamperedSignatureIsRejected() {
        AlipayTestClient.Result result = client.callWithBadSignature("alipay.trade.query", client.newBiz());

        JsonNode error = result.inner(ERROR);
        assertThat(error.path("code").asString()).isEqualTo("40002");
        assertThat(error.path("sub_code").asString()).isEqualTo("isv.invalid-signature");
    }

    @Test
    void disablingRequestSignatureIsReflectedByConfigAndReset() {
        client.putConfig("{\"requireRequestSign\":false}");
        JsonNode config = client.config();
        assertThat(config.path("requireRequestSign").asString()).isEqualTo("false");

        // 关闭验签后，坏签名请求也能进入业务分支（此处为"交易不存在"），说明确实跳过了验签。
        JsonNode query = client.callWithBadSignature("alipay.trade.query", client.newBiz())
                .inner(QUERY);
        assertThat(query.path("code").asString()).isEqualTo("40004");

        client.reset();
        assertThat(client.config().path("requireRequestSign").asString()).isEqualTo("true");
        assertThat(client.callWithBadSignature("alipay.trade.query", client.newBiz())
                .inner(ERROR).path("code").asString()).isEqualTo("40002");
    }

    @Test
    void resetClearsTransactions() {
        client.pay("MOCK-ORDER-6", "3.00", "285612345678901234");
        assertThat(client.config().path("transactions").asString()).isEqualTo("1");

        client.reset();
        assertThat(client.config().path("transactions").asString()).isEqualTo("0");
        assertThat(client.query("MOCK-ORDER-6").inner(QUERY).path("code").asString()).isEqualTo("40004");
    }
}
