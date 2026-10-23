package com.rhn.mock.protocol.fiscal;

import com.rhn.mock.protocol.fiscal.support.FiscalTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/** 开具 / 查询 / 作废 / 冲红的有状态契约，响应字段与 RHN FiscalModels 对齐。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FiscalContractTest {
    @LocalServerPort
    int port;

    FiscalTestClient client;

    @BeforeEach
    void setUp() {
        client = new FiscalTestClient(port);
        client.reset();
    }

    @AfterEach
    void tearDown() {
        client.reset();
    }

    @Test
    void issueReturnsFourElementsAndVerifyUrl() {
        FiscalTestClient.Result issued = client.issue(client.issueBody("张三"));
        assertThat(issued.status()).isEqualTo(200);
        assertThat(issued.success()).isTrue();
        assertThat(issued.text("outcome")).isEqualTo("ISSUED");
        assertThat(issued.text("externalReceiptNo")).startsWith("EINV-");
        assertThat(issued.text("fiscalCode")).matches("\\d{10}");
        assertThat(issued.text("fiscalNumber")).matches("\\d{10}");
        assertThat(issued.text("verificationCode")).matches("\\d{6}");
        assertThat(issued.text("verifyUrl")).contains("bill_code=" + issued.text("fiscalCode"));
        assertThat(issued.text("issuedAt")).endsWith("Z");
        assertThat(issued.node().path("errorCode").isNull()).isTrue();

        // 查验页可用 verifyUrl 的参数验证真伪
        String url = issued.text("verifyUrl");
        FiscalTestClient.Result verified = client.get(url.substring(url.indexOf("/fiscal/verify")));
        assertThat(verified.status()).isEqualTo(200);
        assertThat(verified.node().path("verified").asBoolean()).isTrue();
        assertThat(client.get("/fiscal/verify?bill_code=x&bill_no=y&check_code=z").status()).isEqualTo(404);
    }

    @Test
    void issueIsIdempotentAndConflictsOnDifferentContent() {
        ObjectNode body = client.issueBody("RCP-1", "K-1", "张三");
        FiscalTestClient.Result first = client.issue(body);
        FiscalTestClient.Result second = client.issue(body);
        assertThat(second.text("externalReceiptNo")).isEqualTo(first.text("externalReceiptNo"));
        assertThat(second.text("fiscalNumber")).isEqualTo(first.text("fiscalNumber"));
        assertThat(second.text("verificationCode")).isEqualTo(first.text("verificationCode"));
        assertThat(client.get("/mock/fiscal/receipts").node().size()).isEqualTo(1);

        ObjectNode changed = client.issueBody("RCP-1", "K-1", "张三");
        changed.put("totalAmount", 90);
        FiscalTestClient.Result conflict = client.issue(changed);
        assertThat(conflict.success()).isFalse();
        assertThat(conflict.text("outcome")).isEqualTo("FAILED");
        assertThat(conflict.text("errorCode")).isEqualTo("FISCAL_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void issueRejectsInconsistentAmountsAndMissingFields() {
        ObjectNode badItems = client.issueBody("张三");
        badItems.put("roundingAmount", 1);
        assertThat(client.issue(badItems).text("errorCode")).isEqualTo("FISCAL_AMOUNT_MISMATCH");

        ObjectNode badFunds = client.issueBody("张三");
        badFunds.put("patientAmount", 25);
        assertThat(client.issue(badFunds).text("errorCode")).isEqualTo("FISCAL_AMOUNT_MISMATCH");

        ObjectNode missing = client.issueBody("张三");
        missing.remove("idempotencyKey");
        FiscalTestClient.Result result = client.issue(missing);
        assertThat(result.success()).isFalse();
        assertThat(result.text("errorCode")).isEqualTo("FISCAL_INVALID_ARGUMENT");
    }

    @Test
    void queryByRequestNoOrExternalNoReturnsSameReceipt() {
        FiscalTestClient.Result issued = client.issue(client.issueBody("RCP-Q", "K-Q", "张三"));
        FiscalTestClient.Result byRequest = client.query("RCP-Q", null);
        FiscalTestClient.Result byExternal = client.query(null, issued.text("externalReceiptNo"));
        assertThat(byRequest.success()).isTrue();
        assertThat(byRequest.text("outcome")).isEqualTo("ISSUED");
        assertThat(byRequest.text("fiscalNumber")).isEqualTo(issued.text("fiscalNumber"));
        assertThat(byExternal.text("fiscalCode")).isEqualTo(issued.text("fiscalCode"));

        FiscalTestClient.Result unknown = client.query("NOPE", null);
        assertThat(unknown.success()).isFalse();
        assertThat(unknown.text("errorCode")).isEqualTo("FISCAL_RECEIPT_NOT_FOUND");
    }

    @Test
    void voidThenRepeatVoidOrRedFlushIsRejected() {
        String external = client.issue(client.issueBody("张三")).text("externalReceiptNo");
        FiscalTestClient.Result voided = client.voidReceipt(external, "V-1");
        assertThat(voided.success()).isTrue();
        assertThat(voided.text("externalReceiptNo")).isEqualTo(external);
        assertThat(voided.text("voidedAt")).endsWith("Z");

        // 同一幂等键重放返回同一结果；不同键重复作废被拒
        assertThat(client.voidReceipt(external, "V-1").text("voidedAt")).isEqualTo(voided.text("voidedAt"));
        FiscalTestClient.Result again = client.voidReceipt(external, "V-2");
        assertThat(again.success()).isFalse();
        assertThat(again.text("errorCode")).isEqualTo("FISCAL_ALREADY_VOIDED");

        FiscalTestClient.Result red = client.redFlush(external, "R-1");
        assertThat(red.success()).isFalse();
        assertThat(red.text("errorCode")).isEqualTo("FISCAL_RECEIPT_VOIDED");

        // 查询已作废票据返回明确业务状态 VOIDED
        FiscalTestClient.Result query = client.query(null, external);
        assertThat(query.success()).isTrue();
        assertThat(query.text("outcome")).isEqualTo("VOIDED");
    }

    @Test
    void redFlushIssuesNegativeReceiptOnceAndBlocksFurtherVoidOrFlush() {
        FiscalTestClient.Result issued = client.issue(client.issueBody("张三"));
        String external = issued.text("externalReceiptNo");
        FiscalTestClient.Result red = client.redFlush(external, "R-1");
        assertThat(red.success()).isTrue();
        assertThat(red.text("redExternalReceiptNo")).startsWith("RED-EINV-");
        assertThat(red.text("redFiscalCode")).isEqualTo(issued.text("fiscalCode"));
        assertThat(red.text("redFiscalNumber")).isNotEqualTo(issued.text("fiscalNumber"));
        assertThat(red.text("verificationCode")).matches("\\d{6}");

        assertThat(client.redFlush(external, "R-1").text("redFiscalNumber")).isEqualTo(red.text("redFiscalNumber"));
        FiscalTestClient.Result second = client.redFlush(external, "R-2");
        assertThat(second.success()).isFalse();
        assertThat(second.text("errorCode")).isEqualTo("FISCAL_ALREADY_RED_FLUSHED");
        assertThat(client.voidReceipt(external, "V-1").text("errorCode")).isEqualTo("FISCAL_ALREADY_RED_FLUSHED");
        assertThat(client.redFlush(red.text("redExternalReceiptNo"), "R-3").text("errorCode"))
                .isEqualTo("FISCAL_RED_FLUSH_NOT_ALLOWED");
        assertThat(client.voidReceipt(red.text("redExternalReceiptNo"), "V-9").text("errorCode"))
                .isEqualTo("FISCAL_VOID_NOT_ALLOWED");

        FiscalTestClient.Result query = client.query(null, red.text("redExternalReceiptNo"));
        assertThat(query.text("outcome")).isEqualTo("RED_FLUSHED");
        assertThat(client.get("/mock/fiscal/receipts/" + red.text("redExternalReceiptNo")).text("amount")).isEqualTo("-100.00");
    }

    @Test
    void redFlushRejectsUnknownAndMismatchedOriginal() {
        assertThat(client.redFlush("EINV-NOPE", "R-1").text("errorCode")).isEqualTo("FISCAL_RECEIPT_NOT_FOUND");
        FiscalTestClient.Result issued = client.issue(client.issueBody("张三"));
        ObjectNode flush = new tools.jackson.databind.json.JsonMapper().createObjectNode();
        flush.put("idempotencyKey", "R-9");
        flush.put("originalExternalReceiptNo", issued.text("externalReceiptNo"));
        flush.put("originalFiscalNumber", "0000000000");
        FiscalTestClient.Result mismatch = client.op("redFlush", flush);
        assertThat(mismatch.text("errorCode")).isEqualTo("FISCAL_RECEIPT_MISMATCH");
    }

    @Test
    void credentialsAndBodyAreChecked() {
        assertThat(client.post("/fiscal/v1/receipts/issue", "{}", "mock-fiscal-client", "wrong").status()).isEqualTo(401);
        assertThat(client.post("/fiscal/v1/receipts/issue", "{}", null, null).status()).isEqualTo(401);
        assertThat(client.post("/fiscal/v1/receipts/issue", "not json", "mock-fiscal-client", "mock-fiscal-secret").status())
                .isEqualTo(400);
        assertThat(client.post("/fiscal/v1/receipts/unknown", "{}", "mock-fiscal-client", "mock-fiscal-secret").status())
                .isEqualTo(404);
    }
}
