package com.rhn.mock.protocol.ca;

import com.rhn.mock.protocol.ca.support.CaMockTestBase;
import com.rhn.mock.protocol.ca.support.CaTestClient;
import com.rhn.mock.protocol.ca.support.CaTestClient.Result;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 协议契约：凭据 / 报文外壳、签名人备案与查询、信任材料、时间戳、验签、证书状态。 */
class CaSignerAndTrustContractTest extends CaMockTestBase {

    @Test
    void rejectsMissingOrWrongCredentialsWith401() {
        assertThat(client.post("/ca/v1/trust/anchors", "{}", null, null).status()).isEqualTo(401);
        assertThat(client.post("/ca/v1/trust/anchors", "{}", CaTestClient.CLIENT_ID, "wrong").status()).isEqualTo(401);
        assertThat(client.post("/ca/v1/trust/anchors", "{}", "other", CaTestClient.CLIENT_SECRET).status()).isEqualTo(401);
        assertThat(client.call("trust/anchors", null).succeeded()).isTrue();
    }

    @Test
    void blankClientSecretDisablesCredentialCheck() {
        client.putConfig("{\"clientSecret\":\"\"}");
        assertThat(client.post("/ca/v1/trust/anchors", "{}", null, null).succeeded()).isTrue();
    }

    @Test
    void rejectsNonJsonNonObjectAndUnknownOperation() {
        assertThat(client.callRaw("signers/query", "not json").status()).isEqualTo(400);
        assertThat(client.callRaw("signers/query", "[1,2]").status()).isEqualTo(400);
        assertThat(client.callRaw("signers/unknown", "{}").status()).isEqualTo(404);
    }

    @Test
    void businessFailureIsHttp200WithErrorCode() {
        Result missing = client.call("signers/query", client.obj());
        assertThat(missing.status()).isEqualTo(200);
        assertThat(missing.code()).isEqualTo(-1);
        assertThat(missing.errorCode()).isEqualTo("CA_INVALID_ARGUMENT");
        assertThat(client.call("signers/query", client.obj().put("externalSignerId", "nobody")).errorCode())
                .isEqualTo("CA_SIGNER_NOT_FOUND");
        assertThat(client.enroll("", "x", 1).errorCode()).isEqualTo("CA_INVALID_ARGUMENT");
    }

    @Test
    void enrollIssuesCertificateChainedToExportedRoot() {
        Result result = client.enroll("张医生", "110101199001010011", 7);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.json().path("requestId").asString()).isNotBlank();
        JsonNode data = result.data();
        assertThat(data.path("externalSignerId").asString()).isEqualTo("mock-7");
        assertThat(data.path("state").asString()).isEqualTo("READY");
        X509Certificate certificate = CaCrypto.certificate(data.path("certificate").asString());
        assertThat(certificate.getSubjectX500Principal().getName()).contains("CN=张医生");
        assertThat(CaCrypto.issuedBy(certificate, client.rootCertificate())).isTrue();
        assertThat(CaCrypto.allowsSigning(certificate)).isTrue();
        assertThat(data.path("certificateNotAfter").asString()).isEqualTo(certificate.getNotAfter().toInstant().toString());
    }

    @Test
    void enrollIsIdempotentPerUserAndQueryReturnsSameCertificate() {
        String first = client.enroll("张医生", "110101199001010011", 7).data().path("certificate").asString();
        String again = client.enroll("张医生", "110101199001010011", 7).data().path("certificate").asString();
        assertThat(again).isEqualTo(first);

        Result query = client.call("signers/query", client.obj().put("externalSignerId", "mock-7"));
        assertThat(query.data().path("state").asString()).isEqualTo("READY");
        assertThat(query.data().path("certificate").asString()).isEqualTo(first);
    }

    @Test
    void enrollmentScenariosFollowIdentityNumber() {
        assertThat(client.enroll("a", "TEST-REVIEW", 11).data().path("state").asString()).isEqualTo("PENDING_REVIEW");
        assertThat(client.enroll("a", "TEST-REVIEW", 11).data().path("certificate").isNull()).isTrue();
        assertThat(client.enroll("b", "TEST-REJECT", 12).data().path("state").asString()).isEqualTo("REJECTED");
        assertThat(client.enroll("c", "TEST-CERT-PENDING", 13).data().path("state").asString())
                .isEqualTo("CERTIFICATE_PENDING");
        assertThat(client.enroll("d", "TEST-DISABLED", 14).data().path("state").asString()).isEqualTo("DISABLED");
        Result expired = client.enroll("e", "test-cert-expired", 15);
        assertThat(expired.data().path("state").asString()).isEqualTo("READY");
        assertThat(Instant.parse(expired.data().path("certificateNotAfter").asString())).isBefore(Instant.now());
    }

    @Test
    void approveMovesPendingSignerToReadyWithCertificate() {
        client.enroll("a", "TEST-REVIEW", 11);
        assertThat(client.adminPost("/mock/ca/signers/mock-11/approve", null).status()).isEqualTo(200);

        Result query = client.call("signers/query", client.obj().put("externalSignerId", "mock-11"));
        assertThat(query.data().path("state").asString()).isEqualTo("READY");
        assertThat(query.data().path("certificate").asString()).isNotBlank();
        assertThat(client.adminPost("/mock/ca/signers/mock-11/approve", null).status()).isEqualTo(200);
        client.enroll("b", "TEST-REJECT", 12);
        assertThat(client.adminPost("/mock/ca/signers/mock-12/approve", null).status()).isEqualTo(409);
    }

    @Test
    void revokedScenarioEnrollsReadyThenQueryShowsDisabled() {
        Result enrolled = client.enroll("r", "TEST-REVOKED", 21);
        assertThat(enrolled.data().path("state").asString()).isEqualTo("READY");
        Result query = client.call("signers/query", client.obj().put("externalSignerId", "mock-21"));
        assertThat(query.data().path("state").asString()).isEqualTo("DISABLED");
        assertThat(query.data().path("message").asString()).contains("吊销");
    }

    @Test
    void trustAnchorsExposeRootAndTimestampCertificates() {
        JsonNode data = client.call("trust/anchors", null).data();

        X509Certificate root = CaCrypto.certificate(data.path("certificates").get(0).asString());
        assertThat(CaCrypto.fingerprint(root)).isEqualTo(data.path("rootFingerprint").asString());
        assertThat(root.getBasicConstraints()).isGreaterThanOrEqualTo(0);
        assertThat(CaCrypto.fingerprint(client.rootCertificate())).isEqualTo(data.path("rootFingerprint").asString());
        X509Certificate tsa = CaCrypto.certificate(data.path("timestampCertificate").asString());
        assertThat(CaCrypto.issuedBy(tsa, root)).isTrue();
        assertThat(data.path("timestampAuthority").asString()).isEqualTo("mock-ca-tsa");
    }

    @Test
    void timestampStampAndVerifyRoundTrip() {
        String signature = CaCrypto.b64("some signature".getBytes(StandardCharsets.UTF_8));
        JsonNode stamp = client.call("timestamp/stamp", client.obj().put("signatureValue", signature)).data();
        assertThat(stamp.path("authority").asString()).isEqualTo("mock-ca-tsa");

        ObjectNode verify = client.obj().put("authority", stamp.path("authority").asString())
                .put("token", stamp.path("token").asString()).put("signatureValue", signature);
        JsonNode ok = client.call("timestamp/verify", verify).data();
        assertThat(ok.path("valid").asBoolean()).isTrue();
        assertThat(ok.path("time").asString()).isEqualTo(stamp.path("time").asString());

        verify.put("signatureValue", CaCrypto.b64("another".getBytes(StandardCharsets.UTF_8)));
        assertThat(client.call("timestamp/verify", verify).data().path("valid").asBoolean()).isFalse();
        verify.put("signatureValue", signature).put("authority", "other-tsa");
        assertThat(client.call("timestamp/verify", verify).data().path("valid").asBoolean()).isFalse();
        verify.put("authority", "mock-ca-tsa").put("token", "garbage");
        assertThat(client.call("timestamp/verify", verify).data().path("valid").asBoolean()).isFalse();
    }

    @Test
    void verifySignatureAcceptsStatementSignatureAndRejectsTamper() {
        String signerId = client.enrollNormal("张医生", 7);
        byte[] statement = "RHN-EVIDENCE statement".getBytes(StandardCharsets.UTF_8);
        JsonNode signed = client.clientSign(signerId, CaCrypto.b64(statement)).json();
        ObjectNode request = client.obj().put("format", "SM2_STATEMENT")
                .put("signatureValue", signed.path("signatureValue").asString())
                .put("certificate", signed.path("certificate").asString())
                .put("statement", CaCrypto.b64(statement));

        JsonNode ok = client.call("trust/verify-signature", request).data();
        assertThat(ok.path("valid").asBoolean()).isTrue();
        assertThat(ok.path("externalSignerId").asString()).isEqualTo(signerId);

        request.put("statement", CaCrypto.b64("tampered".getBytes(StandardCharsets.UTF_8)));
        JsonNode bad = client.call("trust/verify-signature", request).data();
        assertThat(bad.path("valid").asBoolean()).isFalse();
        assertThat(bad.path("reasonCode").asString()).isEqualTo("CA_SIGNATURE_INVALID");
    }

    @Test
    void verifySignatureReportsExpiredRevokedAndUntrustedCertificates() {
        byte[] statement = "s".getBytes(StandardCharsets.UTF_8);
        String expiredId = client.enroll("e", "TEST-CERT-EXPIRED", 31).data().path("externalSignerId").asString();
        JsonNode expired = client.clientSign(expiredId, CaCrypto.b64(statement)).json();
        ObjectNode request = client.obj().put("format", "SM2_STATEMENT")
                .put("signatureValue", expired.path("signatureValue").asString())
                .put("certificate", expired.path("certificate").asString())
                .put("statement", CaCrypto.b64(statement));
        assertThat(client.call("trust/verify-signature", request).data().path("reasonCode").asString())
                .isEqualTo("CA_CERTIFICATE_EXPIRED");

        String liveId = client.enrollNormal("l", 32);
        JsonNode live = client.clientSign(liveId, CaCrypto.b64(statement)).json();
        request.put("signatureValue", live.path("signatureValue").asString())
                .put("certificate", live.path("certificate").asString());
        assertThat(client.call("trust/verify-signature", request).data().path("valid").asBoolean()).isTrue();
        client.adminPost("/mock/ca/signers/" + liveId + "/revoke", "{\"reason\":\"KEY_COMPROMISE\"}");
        assertThat(client.call("trust/verify-signature", request).data().path("reasonCode").asString())
                .isEqualTo("CA_CERTIFICATE_REVOKED");

        // 自建根签发的证书：不受信任
        var rogueRoot = CaCrypto.generateKeyPair();
        var rogueKeys = CaCrypto.generateKeyPair();
        X509Certificate rogue = CaCrypto.issue("CN=rogue", rogueKeys.getPublic(), "CN=rogue root", rogueRoot.getPrivate(),
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), false);
        request.put("certificate", CaCrypto.b64(CaCrypto.encoded(rogue)))
                .put("signatureValue", CaCrypto.b64(CaCrypto.sign(rogueKeys.getPrivate(), statement)));
        assertThat(client.call("trust/verify-signature", request).data().path("reasonCode").asString())
                .isEqualTo("CA_CERTIFICATE_UNTRUSTED");
    }

    @Test
    void verifyFailScenarioMakesVendorVerificationReject() {
        String signerId = client.enroll("v", "TEST-VERIFY-FAIL", 41).data().path("externalSignerId").asString();
        byte[] statement = "s".getBytes(StandardCharsets.UTF_8);
        JsonNode signed = client.clientSign(signerId, CaCrypto.b64(statement)).json();
        ObjectNode request = client.obj().put("format", "SM2_STATEMENT")
                .put("signatureValue", signed.path("signatureValue").asString())
                .put("certificate", signed.path("certificate").asString())
                .put("statement", CaCrypto.b64(statement));
        JsonNode verdict = client.call("trust/verify-signature", request).data();
        assertThat(verdict.path("valid").asBoolean()).isFalse();
        assertThat(verdict.path("reasonCode").asString()).isEqualTo("CA_SIGNATURE_INVALID");
    }

    @Test
    void certificateStatusCoversGoodRevokedExpiredAndUntrusted() {
        String goodId = client.enrollNormal("g", 51);
        String goodCert = client.material(goodId).json().path("certificateBase64").asString();
        assertThat(status(goodCert)).isEqualTo("GOOD");
        client.adminPost("/mock/ca/signers/" + goodId + "/revoke", "{\"reason\":\"CESSATION\"}");
        JsonNode revoked = client.call("trust/certificate-status", client.obj().put("certificate", goodCert)).data();
        assertThat(revoked.path("status").asString()).isEqualTo("REVOKED");
        assertThat(revoked.path("revokeReason").asString()).isEqualTo("CESSATION");

        String expired = client.enroll("e", "TEST-CERT-EXPIRED", 52).data().path("certificate").asString();
        assertThat(status(expired)).isEqualTo("EXPIRED");
        var keys = CaCrypto.generateKeyPair();
        X509Certificate rogue = CaCrypto.issue("CN=rogue", keys.getPublic(), "CN=rogue", keys.getPrivate(),
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), false);
        assertThat(status(CaCrypto.b64(CaCrypto.encoded(rogue)))).isEqualTo("UNTRUSTED");
        assertThat(client.call("trust/certificate-status", client.obj().put("certificate", "###")).errorCode())
                .isEqualTo("CA_INVALID_ARGUMENT");
    }

    @Test
    void envelopeBindsChecksVendorAlgorithmAndDigest() {
        byte[] digest = CaCrypto.digest("SM3", "statement".getBytes(StandardCharsets.UTF_8));
        String envelope = "{\"vendor\":\"mock-ca\",\"order\":\"o1\",\"digestAlgorithm\":\"SM3\",\"digest\":\""
                + CaCrypto.b64(digest) + "\"}";
        ObjectNode request = client.obj().put("envelope", envelope).put("digestAlgorithm", "SM3")
                .put("statementDigest", CaCrypto.b64(digest));
        assertThat(client.call("trust/envelope-binds", request).data().path("binds").asBoolean()).isTrue();

        request.put("statementDigest", CaCrypto.b64(CaCrypto.digest("SM3", "other".getBytes(StandardCharsets.UTF_8))));
        assertThat(client.call("trust/envelope-binds", request).data().path("binds").asBoolean()).isFalse();
        request.put("statementDigest", CaCrypto.b64(digest)).put("envelope", "not json");
        assertThat(client.call("trust/envelope-binds", request).data().path("binds").asBoolean()).isFalse();
    }

    private String status(String certificate) {
        return client.call("trust/certificate-status", client.obj().put("certificate", certificate))
                .data().path("status").asString();
    }
}
