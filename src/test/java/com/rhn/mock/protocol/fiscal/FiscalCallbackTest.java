package com.rhn.mock.protocol.fiscal;

import com.rhn.mock.protocol.fiscal.support.FiscalTestClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 异步结果回调：接收端用 RHN 将要实现的方式验签（HMAC-SHA256(secret, timestamp + "." + body)）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FiscalCallbackTest {
    private static final String SECRET = "mock-fiscal-callback-secret";

    record Received(String timestamp, String signature, String body) {
        boolean signatureValid() {
            return signature.equals(FiscalCallbackService.sign(SECRET, timestamp, body));
        }

        JsonNode json() {
            return JsonMapper.builder().build().readTree(body);
        }
    }

    @LocalServerPort
    int port;

    FiscalTestClient client;
    HttpServer receiver;
    final List<Received> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        client = new FiscalTestClient(port);
        client.reset();
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/rhn/fiscal/callback", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestHeaders().getFirst("X-Fiscal-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-Fiscal-Signature"), body));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
        client.putConfig("{\"callbackUrl\":\"http://127.0.0.1:" + receiver.getAddress().getPort() + "/rhn/fiscal/callback\"}");
    }

    @AfterEach
    void tearDown() {
        receiver.stop(0);
        client.reset();
    }

    @Test
    void completingPendingReceiptPushesSignedIssueCallback() {
        FiscalTestClient.Result issued = client.issue(client.issueBody("RCP-CB", "K-CB", "TEST-PENDING"));
        String external = issued.text("externalReceiptNo");
        assertThat(received).isEmpty();

        FiscalTestClient.Result completed = client.postAdmin("/mock/fiscal/receipts/RCP-CB/complete", "{}");
        assertThat(completed.status()).isEqualTo(200);
        assertThat(received).hasSize(1);
        Received callback = received.get(0);
        assertThat(callback.signatureValid()).isTrue();
        JsonNode body = callback.json();
        assertThat(body.path("fiscalAuthorityCode").asString()).isEqualTo("FISCAL");
        assertThat(body.path("operation").asString()).isEqualTo("ISSUE");
        assertThat(body.path("outcome").asString()).isEqualTo("ISSUED");
        assertThat(body.path("receiptRequestNo").asString()).isEqualTo("RCP-CB");
        assertThat(body.path("commandCode").asString()).isEqualTo("K-CB");
        assertThat(body.path("externalReceiptNo").asString()).isEqualTo(external);
        assertThat(body.path("fiscalNumber").asString()).matches("\\d{10}");
        assertThat(body.path("verifyUrl").asString()).contains("bill_no=");
        assertThat(body.path("issuedAt").asString()).endsWith("Z");
        assertThat(body.path("externalMessageBusinessId").asString()).isNotBlank();
        assertThat(client.get("/mock/fiscal/callbacks").node().get(0).path("httpStatus").asInt()).isEqualTo(200);
        // 回调后 RHN 查询得到同一结果
        assertThat(client.query("RCP-CB", null).text("outcome")).isEqualTo("ISSUED");
    }

    @Test
    void duplicateModeSendsSameMessageTwice() {
        client.issue(client.issueBody("RCP-D", "K-D", "张三"));
        assertThat(client.postAdmin("/mock/fiscal/receipts/RCP-D/callback", "{\"mode\":\"duplicate\"}").status()).isEqualTo(200);
        assertThat(received).hasSize(2);
        assertThat(received.get(0).json().path("externalMessageBusinessId").asString())
                .isEqualTo(received.get(1).json().path("externalMessageBusinessId").asString());
        assertThat(received).allMatch(Received::signatureValid);
    }

    @Test
    void badSignatureModeCannotBeVerified() {
        client.issue(client.issueBody("RCP-S", "K-S", "张三"));
        client.postAdmin("/mock/fiscal/receipts/RCP-S/callback", "{\"mode\":\"bad_signature\"}");
        assertThat(received).hasSize(1);
        assertThat(received.get(0).signatureValid()).isFalse();
    }

    @Test
    void voidAndRedFlushCallbacksCarryTheirOperation() {
        String external = client.issue(client.issueBody("RCP-V", "K-V", "张三")).text("externalReceiptNo");
        client.voidReceipt(external, "V-1");
        client.postAdmin("/mock/fiscal/receipts/" + external + "/callback", "{}");
        JsonNode voided = received.get(0).json();
        assertThat(voided.path("operation").asString()).isEqualTo("VOID");
        assertThat(voided.path("outcome").asString()).isEqualTo("VOIDED");
        assertThat(voided.path("commandCode").asString()).isEqualTo("V-1");
        assertThat(voided.path("actionReason").asString()).isEqualTo("作废测试");

        String other = client.issue(client.issueBody("RCP-R", "K-R", "李四")).text("externalReceiptNo");
        String red = client.redFlush(other, "R-1").text("redExternalReceiptNo");
        client.postAdmin("/mock/fiscal/receipts/" + red + "/callback", "{}");
        JsonNode flushed = received.get(1).json();
        assertThat(flushed.path("operation").asString()).isEqualTo("RED_FLUSH");
        assertThat(flushed.path("outcome").asString()).isEqualTo("RED_FLUSHED");
        assertThat(flushed.path("externalReceiptNo").asString()).isEqualTo(red);
    }

    @Test
    void failedCompletionPushesFailedOutcome() {
        client.issue(client.issueBody("RCP-X", "K-X", "TEST-PENDING"));
        client.postAdmin("/mock/fiscal/receipts/RCP-X/complete", "{\"success\":false}");
        JsonNode body = received.get(0).json();
        assertThat(body.path("outcome").asString()).isEqualTo("FAILED");
        assertThat(body.path("errorCode").asString()).isEqualTo("FISCAL_ISSUE_REJECTED");
        assertThat(client.query("RCP-X", null).text("outcome")).isEqualTo("FAILED");
    }

    @Test
    void autoCallbackCompletesAndPushesAfterDelay() throws Exception {
        client.putConfig("{\"autoCallback\":true,\"callbackDelayMs\":100}");
        client.issue(client.issueBody("RCP-AUTO", "K-AUTO", "TEST-PENDING"));
        long deadline = System.currentTimeMillis() + 5000;
        while (received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).json().path("outcome").asString()).isEqualTo("ISSUED");
    }

    @Test
    void callbackWithoutUrlIsRefusedAndUnreachableUrlIsRecorded() {
        client.putConfig("{\"callbackUrl\":\"\"}");
        client.issue(client.issueBody("RCP-N", "K-N", "张三"));
        assertThat(client.postAdmin("/mock/fiscal/receipts/RCP-N/callback", "{}").status()).isEqualTo(409);

        FiscalTestClient.Result unreachable = client.postAdmin("/mock/fiscal/receipts/RCP-N/callback",
                "{\"callbackUrl\":\"http://127.0.0.1:1/none\"}");
        assertThat(unreachable.status()).isEqualTo(200);
        assertThat(unreachable.node().path("httpStatus").asInt()).isZero();
        assertThat(unreachable.node().path("error").asString()).isNotBlank();
    }
}
