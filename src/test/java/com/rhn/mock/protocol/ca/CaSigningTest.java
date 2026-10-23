package com.rhn.mock.protocol.ca;

import com.rhn.mock.protocol.ca.support.CaMockTestBase;
import com.rhn.mock.protocol.ca.support.CaTestClient.Result;
import com.rhn.mock.protocol.ca.support.RhnSideVerifier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;

/** 远程签名任务：幂等提交、推送 / 扫码认领 / 自动签、查询与批量查询、取结果、取消、作废、过期及各签名场景。 */
class CaSigningTest extends CaMockTestBase {
    private static final byte[] STATEMENT = "RHN-EVIDENCE-JSON-1 statement".getBytes(StandardCharsets.UTF_8);

    private String submitPush(String signerId) {
        Result result = client.submit(client.nextSigningOrderId(), signerId, "PUSH", STATEMENT);
        assertThat(result.succeeded()).as(result.body()).isTrue();
        return result.data().path("externalOrderId").asString();
    }

    private String state(String externalOrderId) {
        return client.query(externalOrderId).data().path("state").asString();
    }

    private void verifySigned(String externalOrderId) {
        JsonNode fetched = client.fetch(externalOrderId).data();
        assertThat(RhnSideVerifier.verifyEnvelopeSignature(client.rootCertificate(), tsa(), fetched, "mock-ca", "SM3",
                STATEMENT)).isNull();
    }

    private X509Certificate tsa() {
        return CaCrypto.certificate(CaCrypto.fromPem(client.get("/mock/ca/tsa").body()));
    }

    @Test
    void pushOrderWaitsForSignerThenSignsWithVerifiableEnvelope() {
        String signerId = client.enrollNormal("张医生", 7);
        long signingOrderId = client.nextSigningOrderId();
        Result submitted = client.submit(signingOrderId, signerId, "PUSH", STATEMENT);
        assertThat(submitted.data().path("state").asString()).isEqualTo("AWAITING_SIGNER");
        assertThat(submitted.data().path("claimContent").isNull()).isTrue();
        String orderId = submitted.data().path("externalOrderId").asString();
        assertThat(client.fetch(orderId).errorCode()).isEqualTo("CA_ORDER_NOT_SIGNED");

        // 用 signingOrderId 也能找到任务
        assertThat(client.adminPost("/mock/ca/orders/" + signingOrderId + "/confirm", null).status()).isEqualTo(200);
        assertThat(state(orderId)).isEqualTo("SIGNED");
        JsonNode fetched = client.fetch(orderId).data();
        assertThat(fetched.path("format").asString()).isEqualTo("SM2_ENVELOPE");
        assertThat(fetched.path("timestampAuthority").asString()).isEqualTo("mock-ca-tsa");
        verifySigned(orderId);
        assertThat(fetched.path("certificate").asString())
                .isEqualTo(client.material(signerId).json().path("certificateBase64").asString());
    }

    @Test
    void submitIsIdempotentOnSigningOrderId() {
        String signerId = client.enrollNormal("张医生", 7);
        long signingOrderId = client.nextSigningOrderId();
        String first = client.submit(signingOrderId, signerId, "PUSH", STATEMENT).data().path("externalOrderId").asString();
        String second = client.submit(signingOrderId, signerId, "PUSH", STATEMENT).data().path("externalOrderId").asString();
        assertThat(second).isEqualTo(first);
        assertThat(client.get("/mock/ca/orders").json()).hasSize(1);
    }

    @Test
    void qrClaimOrderExposesClaimContentAndSignsForTheClaimingSigner() {
        String signerId = client.enrollNormal("张医生", 7);
        Result submitted = client.submit(client.nextSigningOrderId(), null, "QR_CLAIM", STATEMENT);
        String orderId = submitted.data().path("externalOrderId").asString();
        assertThat(submitted.data().path("claimContent").asString()).isEqualTo("mock-ca://claim/" + orderId);

        assertThat(client.adminPost("/mock/ca/orders/" + orderId + "/confirm", null).status()).isEqualTo(400);
        assertThat(client.adminPost("/mock/ca/orders/" + orderId + "/confirm",
                "{\"externalSignerId\":\"" + signerId + "\"}").status()).isEqualTo(200);
        assertThat(client.query(orderId).data().path("claimContent").isNull()).isTrue();
        verifySigned(orderId);
    }

    @Test
    void autoModeSignsImmediatelyOnlyWhenGrantApproved() {
        String signerId = client.enrollNormal("张医生", 7);
        ObjectNode grant = client.obj().put("externalSignerId", signerId).put("durationSeconds", 3600);
        assertThat(client.call("grants/query", grant).data().path("state").asString()).isEqualTo("NONE");

        Result before = client.submit(client.nextSigningOrderId(), signerId, "AUTO", STATEMENT);
        assertThat(before.data().path("state").asString()).isEqualTo("AWAITING_SIGNER");

        JsonNode requested = client.call("grants/request", grant).data();
        assertThat(requested.path("qrContent").asString()).isEqualTo("mock-ca://grant/" + signerId);
        assertThat(client.call("grants/query", grant).data().path("state").asString()).isEqualTo("PENDING");
        client.adminPost("/mock/ca/grants/" + signerId + "/approve", null);
        assertThat(client.call("grants/query", grant).data().path("state").asString()).isEqualTo("GRANTED");

        Result after = client.submit(client.nextSigningOrderId(), signerId, "AUTO", STATEMENT);
        assertThat(after.data().path("state").asString()).isEqualTo("SIGNED");
        verifySigned(after.data().path("externalOrderId").asString());

        client.call("grants/revoke", grant);
        assertThat(client.call("grants/query", grant).data().path("state").asString()).isEqualTo("NONE");
        assertThat(client.submit(client.nextSigningOrderId(), signerId, "AUTO", STATEMENT).data().path("state").asString())
                .isEqualTo("AWAITING_SIGNER");
    }

    @Test
    void grantCanBeRefusedByTheSigner() {
        String signerId = client.enrollNormal("张医生", 7);
        ObjectNode grant = client.obj().put("externalSignerId", signerId);
        client.call("grants/request", grant);
        client.adminPost("/mock/ca/grants/" + signerId + "/refuse", null);
        assertThat(client.call("grants/query", grant).data().path("state").asString()).isEqualTo("REFUSED");
        assertThat(client.adminPost("/mock/ca/grants/" + signerId + "/approve", null).status()).isEqualTo(409);
    }

    @Test
    void rejectedCancelledAndExpiredOrdersReachTerminalStates() {
        String signerId = client.enrollNormal("张医生", 7);
        String rejected = submitPush(signerId);
        client.adminPost("/mock/ca/orders/" + rejected + "/reject", null);
        assertThat(state(rejected)).isEqualTo("REJECTED");

        String cancelled = submitPush(signerId);
        assertThat(client.call("signing/cancel", client.obj().put("externalOrderId", cancelled))
                .data().path("state").asString()).isEqualTo("CANCELLED");
        assertThat(client.call("signing/cancel", client.obj().put("externalOrderId", cancelled)).succeeded()).isTrue();
        assertThat(client.adminPost("/mock/ca/orders/" + cancelled + "/confirm", null).status()).isEqualTo(409);

        ObjectNode body = client.obj().put("signingOrderId", client.nextSigningOrderId()).put("externalSignerId", signerId)
                .put("mode", "PUSH").put("digestAlgorithm", "SM3")
                .put("statementDigest", CaCrypto.b64(CaCrypto.digest("SM3", STATEMENT)))
                .put("expiresAt", java.time.Instant.now().minusSeconds(5).toString());
        String expired = client.call("signing/submit", body).data().path("externalOrderId").asString();
        assertThat(state(expired)).isEqualTo("EXPIRED");

        String signed = submitPush(signerId);
        client.adminPost("/mock/ca/orders/" + signed + "/confirm", null);
        assertThat(client.call("signing/cancel", client.obj().put("externalOrderId", signed)).errorCode())
                .isEqualTo("CA_ORDER_NOT_CANCELLABLE");
    }

    @Test
    void signedOrderCanBeVoidedBySigner() {
        String signerId = client.enrollNormal("张医生", 7);
        String orderId = submitPush(signerId);
        client.adminPost("/mock/ca/orders/" + orderId + "/confirm", null);
        assertThat(client.adminPost("/mock/ca/orders/" + orderId + "/void", null).status()).isEqualTo(200);
        assertThat(state(orderId)).isEqualTo("VOIDED_BY_SIGNER");
        assertThat(client.fetch(orderId).errorCode()).isEqualTo("CA_ORDER_NOT_SIGNED");
    }

    @Test
    void batchQueryReportsEachOrderAndEnforcesLimit() {
        String signerId = client.enrollNormal("张医生", 7);
        String first = submitPush(signerId);
        String second = submitPush(signerId);
        client.adminPost("/mock/ca/orders/" + second + "/confirm", null);

        ObjectNode request = client.obj();
        ArrayNode ids = request.putArray("externalOrderIds");
        ids.add(first).add(second).add("missing");
        JsonNode orders = client.call("signing/query-batch", request).data().path("orders");
        assertThat(orders.get(0).path("state").asString()).isEqualTo("AWAITING_SIGNER");
        assertThat(orders.get(1).path("state").asString()).isEqualTo("SIGNED");
        assertThat(orders.get(2).path("state").asString()).isEqualTo("FAILED");
        assertThat(orders.get(2).path("errorCode").asString()).isEqualTo("CA_ORDER_NOT_FOUND");

        ObjectNode tooMany = client.obj();
        ArrayNode many = tooMany.putArray("externalOrderIds");
        for (int i = 0; i < 101; i++) many.add("o" + i);
        assertThat(client.call("signing/query-batch", tooMany).errorCode()).isEqualTo("CA_BATCH_LIMIT_EXCEEDED");
        assertThat(client.call("signing/query-batch", client.obj()).errorCode()).isEqualTo("CA_INVALID_ARGUMENT");
    }

    @Test
    void submitValidatesArgumentsAndSignerState() {
        String signerId = client.enrollNormal("张医生", 7);
        long id = client.nextSigningOrderId();
        assertThat(client.submit(id, signerId, "CLIENT_TOKEN", STATEMENT).errorCode()).isEqualTo("CA_MODE_UNSUPPORTED");
        assertThat(client.submit(id, signerId, "BOGUS", STATEMENT).errorCode()).isEqualTo("CA_INVALID_ARGUMENT");
        assertThat(client.submit(id, null, "PUSH", STATEMENT).errorCode()).isEqualTo("CA_INVALID_ARGUMENT");
        assertThat(client.submit(id, "mock-404", "PUSH", STATEMENT).errorCode()).isEqualTo("CA_SIGNER_NOT_FOUND");
        client.enroll("r", "TEST-REVIEW", 8);
        assertThat(client.submit(id, "mock-8", "PUSH", STATEMENT).errorCode()).isEqualTo("CA_SIGNER_NOT_READY");

        ObjectNode shortDigest = client.obj().put("signingOrderId", id).put("externalSignerId", signerId)
                .put("mode", "PUSH").put("digestAlgorithm", "SM3").put("statementDigest", CaCrypto.b64(new byte[5]));
        assertThat(client.call("signing/submit", shortDigest).errorCode()).isEqualTo("CA_INVALID_ARGUMENT");
        shortDigest.put("digestAlgorithm", "MD5");
        assertThat(client.call("signing/submit", shortDigest).errorCode()).isEqualTo("CA_INVALID_ARGUMENT");
        assertThat(client.call("signing/query", client.obj().put("externalOrderId", "nope")).errorCode())
                .isEqualTo("CA_ORDER_NOT_FOUND");
    }

    @Test
    void revokedSignerIsRefusedAtSubmitAndFailsAnAlreadyPendingOrder() {
        String signerId = client.enrollNormal("张医生", 7);
        String pending = submitPush(signerId);
        client.adminPost("/mock/ca/signers/" + signerId + "/revoke", "{\"reason\":\"KEY_COMPROMISE\"}");

        assertThat(client.submit(client.nextSigningOrderId(), signerId, "PUSH", STATEMENT).errorCode())
                .isEqualTo("CA_CERTIFICATE_REVOKED");
        client.adminPost("/mock/ca/orders/" + pending + "/confirm", null);
        JsonNode failed = client.query(pending).data();
        assertThat(failed.path("state").asString()).isEqualTo("FAILED");
        assertThat(failed.path("errorCode").asString()).isEqualTo("CA_CERTIFICATE_REVOKED");
    }

    @Test
    void signFailScenarioEndsFailedAndSignRejectScenarioEndsRejected() {
        String failId = client.enroll("f", "TEST-SIGN-FAIL", 61).data().path("externalSignerId").asString();
        String failing = submitPush(failId);
        client.adminPost("/mock/ca/orders/" + failing + "/confirm", null);
        JsonNode failed = client.query(failing).data();
        assertThat(failed.path("state").asString()).isEqualTo("FAILED");
        assertThat(failed.path("errorCode").asString()).isEqualTo("CA_SIGN_FAILED");

        String rejectId = client.enroll("r", "TEST-SIGN-REJECT", 62).data().path("externalSignerId").asString();
        String rejecting = submitPush(rejectId);
        client.adminPost("/mock/ca/orders/" + rejecting + "/confirm", null);
        assertThat(state(rejecting)).isEqualTo("REJECTED");
    }

    @Test
    void badSignatureAndBadEnvelopeScenariosFailRhnLocalVerification() {
        String badSigId = client.enroll("s", "TEST-BAD-SIGNATURE", 63).data().path("externalSignerId").asString();
        String sig = submitPush(badSigId);
        client.adminPost("/mock/ca/orders/" + sig + "/confirm", null);
        assertThat(state(sig)).isEqualTo("SIGNED");
        assertThat(RhnSideVerifier.verifyEnvelopeSignature(client.rootCertificate(), tsa(), client.fetch(sig).data(),
                "mock-ca", "SM3", STATEMENT)).isEqualTo("CA_SIGNATURE_INVALID");

        String badEnvId = client.enroll("e", "TEST-BAD-ENVELOPE", 64).data().path("externalSignerId").asString();
        String env = submitPush(badEnvId);
        client.adminPost("/mock/ca/orders/" + env + "/confirm", null);
        JsonNode fetched = client.fetch(env).data();
        assertThat(RhnSideVerifier.verifyEnvelopeSignature(client.rootCertificate(), tsa(), fetched, "mock-ca", "SM3",
                STATEMENT)).isEqualTo("CA_SIGNATURE_INVALID");
        // 签名本身对封装是有效的，只是封装没有绑定本声明摘要
        X509Certificate certificate = CaCrypto.certificate(fetched.path("certificate").asString());
        assertThat(CaCrypto.verify(certificate.getPublicKey(), fetched.path("signedEnvelope").asString().getBytes(StandardCharsets.UTF_8),
                CaCrypto.fromB64(fetched.path("signatureValue").asString()))).isTrue();
    }

    @Test
    void expiredCertificateSignsButRhnLocalVerificationRejectsIt() {
        String signerId = client.enroll("e", "TEST-CERT-EXPIRED", 65).data().path("externalSignerId").asString();
        String orderId = submitPush(signerId);
        client.adminPost("/mock/ca/orders/" + orderId + "/confirm", null);
        assertThat(state(orderId)).isEqualTo("SIGNED");
        assertThat(RhnSideVerifier.verifyEnvelopeSignature(client.rootCertificate(), tsa(), client.fetch(orderId).data(),
                "mock-ca", "SM3", STATEMENT)).isEqualTo("CA_CERTIFICATE_EXPIRED");
    }

    @Test
    void autoConfirmOrdersSignsPendingOrdersWhenQueried() {
        String signerId = client.enrollNormal("张医生", 7);
        client.putConfig("{\"autoConfirmOrders\":true}");
        String orderId = submitPush(signerId);
        assertThat(state(orderId)).isEqualTo("SIGNED");
        verifySigned(orderId);

        // 自动签授权也在被查询时自动批准
        ObjectNode grant = client.obj().put("externalSignerId", signerId);
        client.call("grants/request", grant);
        assertThat(client.call("grants/query", grant).data().path("state").asString()).isEqualTo("GRANTED");
    }

    @Test
    void ordersListExposesSigningLedger() {
        String signerId = client.enrollNormal("张医生", 7);
        String orderId = submitPush(signerId);
        client.adminPost("/mock/ca/orders/" + orderId + "/confirm", null);

        JsonNode orders = client.get("/mock/ca/orders").json();
        assertThat(orders).hasSize(1);
        JsonNode order = orders.get(0);
        assertThat(order.path("externalOrderId").asString()).isEqualTo(orderId);
        assertThat(order.path("state").asString()).isEqualTo("SIGNED");
        assertThat(order.path("signerId").asString()).isEqualTo(signerId);
        assertThat(order.path("digest").asString()).isEqualTo(CaCrypto.b64(CaCrypto.digest("SM3", STATEMENT)));
        assertThat(order.path("subject").asString()).isEqualTo("门诊处方 RX2026");
    }
}
