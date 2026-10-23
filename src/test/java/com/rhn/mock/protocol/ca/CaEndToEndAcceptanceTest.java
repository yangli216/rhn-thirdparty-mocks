package com.rhn.mock.protocol.ca;

import com.rhn.mock.protocol.ca.support.CaMockTestBase;
import com.rhn.mock.protocol.ca.support.CaTestClient.Result;
import com.rhn.mock.protocol.ca.support.RhnSideVerifier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验收：扮演 RHN 适配器，用 Mock 签发的证书走完 备案 → 证书登录挑战 → 扫码登录 → 远程签名 →
 * 取结果 → RHN 本地验签（只用导出的根证书 / 时间戳证书，不调 Mock 的验签接口）→ 厂商交叉验签。
 */
class CaEndToEndAcceptanceTest extends CaMockTestBase {

    @Test
    void certificateLoginThenSignThenVerifyEndToEnd() {
        // 1. 信任锚：从 Mock 导出的根证书（RHN 侧把它配成 trust anchor）
        X509Certificate root = client.rootCertificate();
        X509Certificate tsa = CaCrypto.certificate(client.call("trust/anchors", null).data()
                .path("timestampCertificate").asString());

        // 2. 备案：拿到证书，RHN 据此绑定签名人（指纹）
        Result enrolled = client.enroll("李医生", "110101199001010022", 1001);
        assertThat(enrolled.succeeded()).isTrue();
        String signerId = enrolled.data().path("externalSignerId").asString();
        String certificate = enrolled.data().path("certificate").asString();
        assertThat(RhnSideVerifier.verifyCertificate(root, CaCrypto.certificate(certificate), java.time.Instant.now())).isNull();

        // 3. 证书登录：取一次性挑战 → UKey 替身签名 → 厂商验签 + RHN 本地验签均通过
        JsonNode challenge = client.call("login/certificate/challenge", null).data();
        JsonNode loginSigned = client.clientSign(signerId, challenge.path("toBeSigned").asString()).json();
        assertThat(loginSigned.path("certificate").asString()).isEqualTo(certificate);
        assertThat(RhnSideVerifier.verifyLogin(root, certificate, challenge.path("toBeSigned").asString(),
                loginSigned.path("signatureValue").asString())).isNull();
        ObjectNode verify = client.obj().put("challengeId", challenge.path("challengeId").asString())
                .put("signatureValue", loginSigned.path("signatureValue").asString()).put("certificate", certificate);
        JsonNode verdict = client.call("login/certificate/verify", verify).data();
        assertThat(verdict.path("valid").asBoolean()).isTrue();
        assertThat(verdict.path("externalSignerId").asString()).isEqualTo(signerId);
        // 重放同一份签名被拒
        assertThat(client.call("login/certificate/verify", verify).data().path("reasonCode").asString())
                .isEqualTo("CA_CHALLENGE_REPLAYED");

        // 4. 扫码登录：start → 医生手机确认 → poll 拿到同一张证书
        String requestId = client.call("login/mobile/start", client.obj()).data().path("externalRequestId").asString();
        client.adminPost("/mock/ca/logins/" + requestId + "/confirm", "{\"externalSignerId\":\"" + signerId + "\"}");
        JsonNode mobile = client.call("login/mobile/poll", client.obj().put("externalRequestId", requestId)).data();
        assertThat(mobile.path("state").asString()).isEqualTo("CONFIRMED");
        assertThat(mobile.path("certificate").asString()).isEqualTo(certificate);

        // 5. 开启自动签后提交签名任务（只发摘要），任务立即 SIGNED
        byte[] statement = "RHN-EVIDENCE-JSON-1 {\"action\":\"PRESCRIPTION_SUBMIT\",\"id\":42}".getBytes(StandardCharsets.UTF_8);
        ObjectNode grant = client.obj().put("externalSignerId", signerId).put("durationSeconds", 14400);
        client.call("grants/request", grant);
        client.adminPost("/mock/ca/grants/" + signerId + "/approve", null);
        Result submitted = client.submit(client.nextSigningOrderId(), signerId, "AUTO", statement);
        assertThat(submitted.data().path("state").asString()).isEqualTo("SIGNED");
        String orderId = submitted.data().path("externalOrderId").asString();
        assertThat(client.query(orderId).data().path("state").asString()).isEqualTo("SIGNED");

        // 6. 取结果 → RHN 本地验签：证书链、封装绑定声明摘要、SM2 签名、可信时间戳
        JsonNode fetched = client.fetch(orderId).data();
        assertThat(fetched.path("certificate").asString()).isEqualTo(certificate);
        assertThat(RhnSideVerifier.verifyEnvelopeSignature(root, tsa, fetched, "mock-ca", "SM3", statement)).isNull();
        // 篡改声明则本地验签失败
        assertThat(RhnSideVerifier.verifyEnvelopeSignature(root, tsa, fetched, "mock-ca", "SM3",
                "RHN-EVIDENCE-JSON-1 {\"id\":43}".getBytes(StandardCharsets.UTF_8))).isEqualTo("CA_SIGNATURE_INVALID");

        // 7. 厂商交叉验签与时间戳核对也一致
        ObjectNode cross = client.obj().put("format", "SM2_ENVELOPE")
                .put("signatureValue", fetched.path("signatureValue").asString())
                .put("certificate", certificate).put("signedEnvelope", fetched.path("signedEnvelope").asString())
                .put("digestAlgorithm", "SM3").put("statement", CaCrypto.b64(statement));
        assertThat(client.call("trust/verify-signature", cross).data().path("valid").asBoolean()).isTrue();
        ObjectNode stamp = client.obj().put("authority", fetched.path("timestampAuthority").asString())
                .put("token", fetched.path("timestampToken").asString())
                .put("signatureValue", fetched.path("signatureValue").asString());
        assertThat(client.call("timestamp/verify", stamp).data().path("valid").asBoolean()).isTrue();

        // 8. 证书吊销后：状态为 REVOKED，登录与新签名被拒
        client.adminPost("/mock/ca/signers/" + signerId + "/revoke", "{\"reason\":\"CESSATION\"}");
        assertThat(client.call("trust/certificate-status", client.obj().put("certificate", certificate))
                .data().path("status").asString()).isEqualTo("REVOKED");
        assertThat(client.submit(client.nextSigningOrderId(), signerId, "PUSH", statement).errorCode())
                .isEqualTo("CA_CERTIFICATE_REVOKED");

        // 9. 全程可在已收报文里对账；签名流水可查
        assertThat(client.get("/mock/requests?protocol=ca&op=signing/submit").json()).hasSize(2);
        JsonNode ledger = client.get("/mock/ca/orders").json();
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).path("state").asString()).isEqualTo("SIGNED");
    }

    @Test
    void autoConfirmModeLetsRhnRunTheWholeFlowWithoutManualPhoneSteps() {
        String signerId = client.enrollNormal("王医生", 2002);
        client.putConfig("{\"autoConfirmOrders\":true,\"autoConfirmLoginSigner\":\"" + signerId + "\"}");

        String requestId = client.call("login/mobile/start", client.obj()).data().path("externalRequestId").asString();
        JsonNode login = client.call("login/mobile/poll", client.obj().put("externalRequestId", requestId)).data();
        assertThat(login.path("state").asString()).isEqualTo("CONFIRMED");

        byte[] statement = "statement".getBytes(StandardCharsets.UTF_8);
        String orderId = client.submit(client.nextSigningOrderId(), signerId, "PUSH", statement)
                .data().path("externalOrderId").asString();
        assertThat(client.query(orderId).data().path("state").asString()).isEqualTo("SIGNED");
        X509Certificate tsa = CaCrypto.certificate(client.call("trust/anchors", null).data()
                .path("timestampCertificate").asString());
        assertThat(RhnSideVerifier.verifyEnvelopeSignature(client.rootCertificate(), tsa, client.fetch(orderId).data(),
                "mock-ca", "SM3", statement)).isNull();
    }
}
