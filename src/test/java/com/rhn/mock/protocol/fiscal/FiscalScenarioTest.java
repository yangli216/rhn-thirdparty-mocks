package com.rhn.mock.protocol.fiscal;

import com.rhn.mock.protocol.fiscal.support.FiscalTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/** 场景与故障注入、管理端点。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FiscalScenarioTest {
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
    void pendingScenarioReturnsProcessingThenIssuedOnQuery() {
        FiscalTestClient.Result issued = client.issue(client.issueBody("RCP-P", "K-P", "TEST-PENDING"));
        assertThat(issued.status()).isEqualTo(200);
        assertThat(issued.success()).isFalse();
        assertThat(issued.text("outcome")).isEqualTo("PENDING");
        assertThat(issued.text("errorCode")).isEqualTo("FISCAL_PROCESSING");
        String external = issued.text("externalReceiptNo");
        assertThat(external).startsWith("EINV-");
        assertThat(issued.node().path("fiscalNumber").isNull()).isTrue();

        // 重复开具仍是同一张处理中票据；处理中不可作废 / 冲红
        assertThat(client.issue(client.issueBody("RCP-P", "K-P", "TEST-PENDING")).text("externalReceiptNo")).isEqualTo(external);
        assertThat(client.voidReceipt(external, "V-1").text("errorCode")).isEqualTo("FISCAL_NOT_ISSUED");
        assertThat(client.redFlush(external, "R-1").text("errorCode")).isEqualTo("FISCAL_NOT_ISSUED");

        FiscalTestClient.Result first = client.query("RCP-P", null);
        assertThat(first.text("outcome")).isEqualTo("PENDING");
        FiscalTestClient.Result second = client.query("RCP-P", null);
        assertThat(second.success()).isTrue();
        assertThat(second.text("outcome")).isEqualTo("ISSUED");
        assertThat(second.text("externalReceiptNo")).isEqualTo(external);
        assertThat(second.text("fiscalNumber")).matches("\\d{10}");
    }

    @Test
    void pendingQueriesIsConfigurable() {
        client.putConfig("{\"issueScenario\":\"PENDING\",\"pendingQueries\":0}");
        client.issue(client.issueBody("RCP-C", "K-C", "张三"));
        assertThat(client.query("RCP-C", null).text("outcome")).isEqualTo("ISSUED");
    }

    @Test
    void failScenarioRejectsAndCanBeRetried() {
        FiscalTestClient.Result failed = client.issue(client.issueBody("RCP-F", "K-F", "TEST-FAIL"));
        assertThat(failed.success()).isFalse();
        assertThat(failed.text("outcome")).isEqualTo("FAILED");
        assertThat(failed.text("errorCode")).isEqualTo("FISCAL_ISSUE_REJECTED");
        assertThat(client.get("/mock/fiscal/receipts").node().size()).isZero();
        // 失败不占用幂等键，同一键换个付款人即可成功
        assertThat(client.issue(client.issueBody("RCP-F", "K-F", "张三")).success()).isTrue();
    }

    @Test
    void payerFaultTokensProduceHttpLevelFaults() {
        FiscalTestClient.Result http500 = client.issue(client.issueBody("TEST-HTTP500"));
        assertThat(http500.status()).isEqualTo(500);
        FiscalTestClient.Result badJson = client.issue(client.issueBody("TEST-BADJSON"));
        assertThat(badJson.status()).isEqualTo(200);
        assertThat(badJson.json()).isNull();
        assertThat(client.get("/mock/fiscal/receipts").node().size()).isZero();
    }

    @Test
    void globalFaultAppliesToChosenOperationOnly() {
        String external = client.issue(client.issueBody("张三")).text("externalReceiptNo");
        client.putConfig("{\"fault\":\"HTTP500\",\"faultOp\":\"query\"}");
        assertThat(client.query(null, external).status()).isEqualTo(500);
        assertThat(client.voidReceipt(external, "V-1").success()).isTrue();

        client.putConfig("{\"fault\":\"SLOW\",\"faultOp\":\"query\",\"slowDelayMs\":300}");
        long start = System.currentTimeMillis();
        assertThat(client.query(null, external).success()).isTrue();
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(280);

        client.putConfig("{\"fault\":\"TIMEOUT\",\"faultOp\":\"query\",\"timeoutDelayMs\":300}");
        start = System.currentTimeMillis();
        client.query(null, external);
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(280);
    }

    @Test
    void forcedBusinessErrorAndInvalidConfigValues() {
        client.putConfig("{\"forceErrorCode\":\"FISCAL_BUSY\",\"forceMessage\":\"平台繁忙\"}");
        FiscalTestClient.Result forced = client.issue(client.issueBody("张三"));
        assertThat(forced.success()).isFalse();
        assertThat(forced.text("errorCode")).isEqualTo("FISCAL_BUSY");
        assertThat(forced.text("errorMessage")).isEqualTo("平台繁忙");
        assertThat(client.putConfig("{\"fault\":\"BOGUS\"}").status()).isGreaterThanOrEqualTo(400);
    }

    @Test
    void adminEndpointsShowReceiptsRequestsAndReset() {
        FiscalTestClient.Result issued = client.issue(client.issueBody("RCP-A", "K-A", "张三"));
        String external = issued.text("externalReceiptNo");
        assertThat(client.get("/mock/fiscal/receipts/RCP-A").text("externalReceiptNo")).isEqualTo(external);
        assertThat(client.get("/mock/fiscal/receipts/NOPE").status()).isEqualTo(404);

        FiscalTestClient.Result requests = client.get("/mock/requests?protocol=fiscal&op=issue");
        assertThat(requests.node().size()).isEqualTo(1);
        assertThat(requests.node().get(0).path("refId").asString()).isEqualTo("RCP-A");

        assertThat(client.get("/mock/health").body()).contains("fiscal");
        assertThat(client.get("/mock/config?protocol=fiscal").node().path("receipts").asInt()).isEqualTo(1);
        client.putConfig("{\"issueScenario\":\"FAIL\"}");
        client.reset();
        assertThat(client.get("/mock/config?protocol=fiscal").node().path("issueScenario").asString()).isEqualTo("NORMAL");
        assertThat(client.get("/mock/fiscal/receipts").node().size()).isZero();
        assertThat(client.get("/mock/requests?protocol=fiscal").node().size()).isZero();
    }
}
