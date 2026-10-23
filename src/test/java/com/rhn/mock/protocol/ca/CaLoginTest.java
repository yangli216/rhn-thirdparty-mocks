package com.rhn.mock.protocol.ca;

import com.rhn.mock.protocol.ca.support.CaMockTestBase;
import com.rhn.mock.protocol.ca.support.CaTestClient.Result;
import com.rhn.mock.protocol.ca.support.RhnSideVerifier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 扫码登录（一次性领取、拒绝、过期、吊销、自动确认）与证书登录挑战（重放、过期、验签失败、证书异常）。 */
class CaLoginTest extends CaMockTestBase {

    private JsonNode startMobile() {
        return client.call("login/mobile/start", client.obj().put("enableAutoSign", false)).data();
    }

    private JsonNode poll(String requestId) {
        return client.call("login/mobile/poll", client.obj().put("externalRequestId", requestId)).data();
    }

    private Result confirm(String requestId, String signerId) {
        return client.adminPost("/mock/ca/logins/" + requestId + "/confirm", "{\"externalSignerId\":\"" + signerId + "\"}");
    }

    @Test
    void mobileLoginIsPendingThenConfirmedAndClaimableOnce() {
        String signerId = client.enrollNormal("张医生", 7);
        JsonNode started = startMobile();
        String requestId = started.path("externalRequestId").asString();
        assertThat(started.path("qrContent").asString()).isEqualTo("mock-ca://login/" + requestId);
        assertThat(Instant.parse(started.path("expiresAt").asString())).isAfter(Instant.now().plusSeconds(100));
        assertThat(poll(requestId).path("state").asString()).isEqualTo("PENDING");

        assertThat(confirm(requestId, signerId).status()).isEqualTo(200);
        JsonNode confirmed = poll(requestId);
        assertThat(confirmed.path("state").asString()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("externalSignerId").asString()).isEqualTo(signerId);
        assertThat(confirmed.path("certificate").asString())
                .isEqualTo(client.material(signerId).json().path("certificateBase64").asString());

        assertThat(poll(requestId).path("state").asString()).isEqualTo("EXPIRED");
    }

    @Test
    void mobileLoginCanBeRejectedExpiredOrUnknown() {
        String requestId = startMobile().path("externalRequestId").asString();
        assertThat(client.adminPost("/mock/ca/logins/" + requestId + "/reject", null).status()).isEqualTo(200);
        JsonNode rejected = poll(requestId);
        assertThat(rejected.path("state").asString()).isEqualTo("REJECTED");
        assertThat(rejected.path("certificate").isNull()).isTrue();

        String second = startMobile().path("externalRequestId").asString();
        client.adminPost("/mock/ca/logins/" + second + "/expire", null);
        assertThat(poll(second).path("state").asString()).isEqualTo("EXPIRED");
        assertThat(poll("mock-login-unknown").path("state").asString()).isEqualTo("EXPIRED");
    }

    @Test
    void shortChallengeTtlExpiresPendingLogin() throws InterruptedException {
        client.putConfig("{\"loginChallengeTtlSeconds\":1}");
        String requestId = startMobile().path("externalRequestId").asString();
        Thread.sleep(1200);
        assertThat(poll(requestId).path("state").asString()).isEqualTo("EXPIRED");
        assertThat(confirm(requestId, "mock-1").status()).isIn(404, 409);
    }

    @Test
    void revokedOrUnreadySignerCannotConfirmLogin() {
        String signerId = client.enrollNormal("张医生", 7);
        client.adminPost("/mock/ca/signers/" + signerId + "/revoke", null);
        String requestId = startMobile().path("externalRequestId").asString();
        confirm(requestId, signerId);
        JsonNode result = poll(requestId);
        assertThat(result.path("state").asString()).isEqualTo("REJECTED");
        assertThat(result.path("message").asString()).contains("吊销");

        client.enroll("d", "TEST-DISABLED", 8);
        String second = startMobile().path("externalRequestId").asString();
        confirm(second, "mock-8");
        assertThat(poll(second).path("state").asString()).isEqualTo("REJECTED");
        assertThat(confirm("mock-login-missing", signerId).status()).isEqualTo(409);
    }

    @Test
    void autoConfirmLoginSignerConfirmsOnPoll() {
        String signerId = client.enrollNormal("张医生", 7);
        client.putConfig("{\"autoConfirmLoginSigner\":\"" + signerId + "\"}");
        String requestId = startMobile().path("externalRequestId").asString();
        JsonNode result = poll(requestId);
        assertThat(result.path("state").asString()).isEqualTo("CONFIRMED");
        assertThat(result.path("externalSignerId").asString()).isEqualTo(signerId);
    }

    @Test
    void autoSignFlagIsReportedWhenRequested() {
        String signerId = client.enrollNormal("张医生", 7);
        String requestId = client.call("login/mobile/start", client.obj().put("enableAutoSign", true).put("autoSignHours", 4))
                .data().path("externalRequestId").asString();
        confirm(requestId, signerId);
        assertThat(poll(requestId).path("autoSignGranted").asBoolean()).isTrue();
    }

    // ==================== 证书登录 ====================

    private JsonNode challenge() {
        return client.call("login/certificate/challenge", null).data();
    }

    private JsonNode verify(String challengeId, String signature, String certificate) {
        ObjectNode body = client.obj().put("challengeId", challengeId).put("signatureValue", signature)
                .put("certificate", certificate);
        return client.call("login/certificate/verify", body).data();
    }

    @Test
    void certificateLoginVerifiesSignatureOverChallenge() {
        String signerId = client.enrollNormal("张医生", 7);
        JsonNode challenge = challenge();
        JsonNode signed = client.clientSign(signerId, challenge.path("toBeSigned").asString()).json();

        JsonNode verdict = verify(challenge.path("challengeId").asString(), signed.path("signatureValue").asString(),
                signed.path("certificate").asString());

        assertThat(verdict.path("valid").asBoolean()).isTrue();
        assertThat(verdict.path("externalSignerId").asString()).isEqualTo(signerId);
        // RHN 侧用导出的根证书独立验得过
        assertThat(RhnSideVerifier.verifyLogin(client.rootCertificate(), signed.path("certificate").asString(),
                challenge.path("toBeSigned").asString(), signed.path("signatureValue").asString())).isNull();
    }

    @Test
    void challengeIsSingleUseAndReplayIsRejected() {
        String signerId = client.enrollNormal("张医生", 7);
        JsonNode challenge = challenge();
        JsonNode signed = client.clientSign(signerId, challenge.path("toBeSigned").asString()).json();
        String id = challenge.path("challengeId").asString();
        assertThat(verify(id, signed.path("signatureValue").asString(), signed.path("certificate").asString())
                .path("valid").asBoolean()).isTrue();

        JsonNode replay = verify(id, signed.path("signatureValue").asString(), signed.path("certificate").asString());
        assertThat(replay.path("valid").asBoolean()).isFalse();
        assertThat(replay.path("reasonCode").asString()).isEqualTo("CA_CHALLENGE_REPLAYED");
        assertThat(verify("mock-chal-none", "AA==", signed.path("certificate").asString()).path("reasonCode").asString())
                .isEqualTo("CA_CHALLENGE_NOT_FOUND");
    }

    @Test
    void expiredChallengeIsRejected() {
        String signerId = client.enrollNormal("张医生", 7);
        JsonNode challenge = challenge();
        String id = challenge.path("challengeId").asString();
        client.adminPost("/mock/ca/challenges/" + id + "/expire", null);
        JsonNode signed = client.clientSign(signerId, challenge.path("toBeSigned").asString()).json();

        JsonNode verdict = verify(id, signed.path("signatureValue").asString(), signed.path("certificate").asString());
        assertThat(verdict.path("valid").asBoolean()).isFalse();
        assertThat(verdict.path("reasonCode").asString()).isEqualTo("CA_CHALLENGE_EXPIRED");
    }

    @Test
    void wrongSignatureConsumesTheChallengeAndFails() {
        String signerId = client.enrollNormal("张医生", 7);
        JsonNode challenge = challenge();
        // 对别的内容签名：验签不通过，且挑战已被消耗
        JsonNode signed = client.clientSign(signerId, CaCrypto.b64("other content".getBytes())).json();
        String id = challenge.path("challengeId").asString();

        JsonNode verdict = verify(id, signed.path("signatureValue").asString(), signed.path("certificate").asString());
        assertThat(verdict.path("reasonCode").asString()).isEqualTo("CA_SIGNATURE_INVALID");
        assertThat(verify(id, signed.path("signatureValue").asString(), signed.path("certificate").asString())
                .path("reasonCode").asString()).isEqualTo("CA_CHALLENGE_REPLAYED");
    }

    @Test
    void expiredRevokedAndUntrustedCertificatesFailCertificateLogin() {
        String expiredId = client.enroll("e", "TEST-CERT-EXPIRED", 8).data().path("externalSignerId").asString();
        JsonNode first = challenge();
        JsonNode signed = client.clientSign(expiredId, first.path("toBeSigned").asString()).json();
        assertThat(verify(first.path("challengeId").asString(), signed.path("signatureValue").asString(),
                signed.path("certificate").asString()).path("reasonCode").asString()).isEqualTo("CA_CERTIFICATE_EXPIRED");

        String revokedId = client.enroll("r", "TEST-REVOKED", 9).data().path("externalSignerId").asString();
        JsonNode second = challenge();
        JsonNode revoked = client.clientSign(revokedId, second.path("toBeSigned").asString()).json();
        assertThat(verify(second.path("challengeId").asString(), revoked.path("signatureValue").asString(),
                revoked.path("certificate").asString()).path("reasonCode").asString()).isEqualTo("CA_CERTIFICATE_REVOKED");

        JsonNode third = challenge();
        var keys = CaCrypto.generateKeyPair();
        var rogue = CaCrypto.issue("CN=rogue", keys.getPublic(), "CN=rogue", keys.getPrivate(),
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), false);
        String rogueSignature = CaCrypto.b64(CaCrypto.sign(keys.getPrivate(), CaCrypto.fromB64(third.path("toBeSigned").asString())));
        assertThat(verify(third.path("challengeId").asString(), rogueSignature, CaCrypto.b64(CaCrypto.encoded(rogue)))
                .path("reasonCode").asString()).isEqualTo("CA_CERTIFICATE_UNTRUSTED");

        JsonNode fourth = challenge();
        assertThat(verify(fourth.path("challengeId").asString(), "!!", "###").path("reasonCode").asString())
                .isEqualTo("CA_SIGNATURE_MALFORMED");
    }

    @Test
    void verifyFailScenarioRejectsOtherwiseValidLogin() {
        String signerId = client.enroll("v", "TEST-VERIFY-FAIL", 10).data().path("externalSignerId").asString();
        JsonNode challenge = challenge();
        JsonNode signed = client.clientSign(signerId, challenge.path("toBeSigned").asString()).json();
        JsonNode verdict = verify(challenge.path("challengeId").asString(), signed.path("signatureValue").asString(),
                signed.path("certificate").asString());
        assertThat(verdict.path("valid").asBoolean()).isFalse();
        assertThat(verdict.path("reasonCode").asString()).isEqualTo("CA_SIGNATURE_INVALID");
    }
}
