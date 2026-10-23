package com.rhn.mock.protocol.ca;

import com.rhn.mock.protocol.ca.support.CaMockTestBase;
import com.rhn.mock.protocol.ca.support.CaTestClient.Result;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/** 故障注入：全局运行时开关（HTTP500 / 非 JSON / 慢响应 / 超时 / 强制失败）与签名人维度场景。 */
class CaFaultInjectionTest extends CaMockTestBase {

    private Result anchors() {
        return client.call("trust/anchors", null);
    }

    @Test
    void globalHttp500AndBadJsonAffectAllOperations() {
        client.putConfig("{\"fault\":\"HTTP500\"}");
        assertThat(anchors().status()).isEqualTo(500);
        assertThat(client.enroll("a", "x", 1).status()).isEqualTo(500);

        client.putConfig("{\"fault\":\"BADJSON\"}");
        Result bad = anchors();
        assertThat(bad.status()).isEqualTo(200);
        assertThat(bad.json()).isNull();
        assertThat(bad.body()).contains("not a json");

        client.putConfig("{\"fault\":\"NONE\"}");
        assertThat(anchors().succeeded()).isTrue();
    }

    @Test
    void faultCanBeLimitedToOneOperation() {
        client.putConfig("{\"fault\":\"HTTP500\",\"faultOp\":\"signing/submit\"}");
        assertThat(anchors().succeeded()).isTrue();
        String signerId = client.enrollNormal("张医生", 7);
        assertThat(client.submit(client.nextSigningOrderId(), signerId, "PUSH", new byte[]{1}).status()).isEqualTo(500);
    }

    @Test
    void slowAndTimeoutFaultsDelayButStillAnswer() {
        client.putConfig("{\"fault\":\"SLOW\",\"slowDelayMs\":300}");
        long start = System.nanoTime();
        assertThat(anchors().succeeded()).isTrue();
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(280);

        client.putConfig("{\"fault\":\"TIMEOUT\",\"timeoutDelayMs\":500}");
        start = System.nanoTime();
        assertThat(anchors().succeeded()).isTrue();
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(480);

        client.putConfig("{\"fault\":\"NONE\",\"forceDelayMs\":250}");
        start = System.nanoTime();
        assertThat(anchors().succeeded()).isTrue();
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(230);
    }

    @Test
    void timeoutCanTripAClientSideReadTimeout() {
        client.putConfig("{\"fault\":\"TIMEOUT\",\"timeoutDelayMs\":1500}");
        var shortClient = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/ca/v1/trust/anchors"))
                .timeout(java.time.Duration.ofMillis(400)).header("X-Client-Id", "mock-ca-client")
                .header("X-Client-Secret", "mock-ca-secret")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> shortClient.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString())).isInstanceOf(java.net.http.HttpTimeoutException.class);
    }

    @Test
    void forcedErrorOverridesBusinessReply() {
        client.putConfig("{\"forceErrorCode\":\"CA_VENDOR_BUSY\",\"forceMessage\":\"厂商繁忙\"}");
        Result forced = anchors();
        assertThat(forced.status()).isEqualTo(200);
        assertThat(forced.code()).isEqualTo(-1);
        assertThat(forced.errorCode()).isEqualTo("CA_VENDOR_BUSY");
        assertThat(forced.json().path("message").asString()).isEqualTo("厂商繁忙");

        client.putConfig("{\"forceErrorCode\":null,\"forceMessage\":null}");
        assertThat(anchors().succeeded()).isTrue();
    }

    @Test
    void invalidFaultNameIsRejectedByConfigUpdate() {
        assertThat(client.putConfig("{\"fault\":\"BOOM\"}").status()).isEqualTo(500);
        assertThat(client.get("/mock/config?protocol=ca").json().path("fault").asString()).isEqualTo("NONE");
    }

    @Test
    void signerScenariosInjectHttpFaultsForThatSignerOnly() {
        assertThat(client.enroll("a", "TEST-HTTP500", 71).status()).isEqualTo(500);
        assertThat(client.enroll("b", "TEST-BADJSON", 72).json()).isNull();
        assertThat(client.enroll("c", "TEST-NORMAL-ONE", 73).succeeded()).isTrue();

        // 场景人员一旦存在，后续针对它的调用同样故障；其他人不受影响
        client.issueSigner("slow", 74, "HTTP500");
        ObjectNode query = client.obj().put("externalSignerId", "mock-74");
        assertThat(client.call("signers/query", query).status()).isEqualTo(500);
        assertThat(client.call("signers/query", client.obj().put("externalSignerId", "mock-73")).succeeded()).isTrue();
    }

    @Test
    void signerSlowAndTimeoutScenariosUseConfiguredDelays() {
        client.putConfig("{\"slowDelayMs\":300,\"timeoutDelayMs\":500}");
        long start = System.nanoTime();
        assertThat(client.enroll("s", "TEST-SLOW", 75).succeeded()).isTrue();
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(280);
        start = System.nanoTime();
        assertThat(client.enroll("t", "TEST-TIMEOUT", 76).succeeded()).isTrue();
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(480);
    }

    @Test
    void faultedExchangesAreStillLogged() {
        client.putConfig("{\"fault\":\"HTTP500\"}");
        anchors();
        JsonNode entries = client.get("/mock/requests?protocol=ca&op=trust/anchors").json();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).path("httpStatus").asInt()).isEqualTo(500);
    }
}
