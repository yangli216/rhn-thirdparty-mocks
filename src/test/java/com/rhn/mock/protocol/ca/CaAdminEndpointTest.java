package com.rhn.mock.protocol.ca;

import com.rhn.mock.protocol.ca.support.CaMockTestBase;
import com.rhn.mock.protocol.ca.support.CaTestClient.Result;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;

/** 管理端点：统一清单登记、reset、已收报文、配置读写，以及 CA 测试辅助（根证书 / 证书私钥导出、签发、吊销）。 */
class CaAdminEndpointTest extends CaMockTestBase {

    @Test
    void caIsRegisteredInHealthAndAllConfigView() {
        JsonNode health = client.get("/mock/health").json();
        assertThat(health.path("protocols").toString()).contains("\"ca\"");
        JsonNode all = client.get("/mock/config?protocol=all").json();
        assertThat(all.has("ca")).isTrue();
        assertThat(all.path("chs").has("xToken")).isTrue();
    }

    @Test
    void configSupportsReadPartialUpdateAndResetToDefaults() {
        JsonNode defaults = client.get("/mock/config?protocol=ca").json();
        assertThat(defaults.path("basePath").asString()).isEqualTo("/ca");
        assertThat(defaults.path("vendorCode").asString()).isEqualTo("mock-ca");
        assertThat(defaults.path("fault").asString()).isEqualTo("NONE");
        assertThat(defaults.path("loginChallengeTtlSeconds").asInt()).isEqualTo(120);
        assertThat(defaults.path("autoConfirmOrders").asBoolean()).isFalse();
        assertThat(defaults.path("rootFingerprint").asString()).hasSize(64);

        JsonNode updated = client.putConfig("{\"fault\":\"slow\",\"slowDelayMs\":10,\"autoConfirmOrders\":true}").json();
        assertThat(updated.path("fault").asString()).isEqualTo("SLOW");
        assertThat(updated.path("slowDelayMs").asInt()).isEqualTo(10);
        assertThat(updated.path("autoConfirmOrders").asBoolean()).isTrue();
        assertThat(updated.path("timeoutDelayMs").asInt()).isEqualTo(31000);

        client.reset();
        JsonNode after = client.get("/mock/config?protocol=ca").json();
        assertThat(after.path("fault").asString()).isEqualTo("NONE");
        assertThat(after.path("autoConfirmOrders").asBoolean()).isFalse();
    }

    @Test
    void resetClearsSignersOrdersAndExchangeLog() {
        String signerId = client.enrollNormal("张医生", 7);
        client.submit(client.nextSigningOrderId(), signerId, "PUSH", new byte[]{1});
        assertThat(client.get("/mock/ca/signers").json()).hasSize(1);
        assertThat(client.get("/mock/ca/orders").json()).hasSize(1);

        Result reset = client.adminPost("/mock/reset", "{}");
        assertThat(reset.json().path("reset").toString()).contains("ca");
        assertThat(client.get("/mock/ca/signers").json()).isEmpty();
        assertThat(client.get("/mock/ca/orders").json()).isEmpty();
        assertThat(client.get("/mock/requests?protocol=ca").json()).isEmpty();
    }

    @Test
    void requestsShowRawExchangesFilteredByOperation() {
        String signerId = client.enrollNormal("张医生", 7);
        client.submit(client.nextSigningOrderId(), signerId, "PUSH", new byte[]{1});
        client.call("trust/anchors", null);

        JsonNode all = client.get("/mock/requests?protocol=ca").json();
        assertThat(all).hasSize(3);
        assertThat(all.get(0).path("op").asString()).isEqualTo("trust/anchors");
        JsonNode submits = client.get("/mock/requests?protocol=ca&op=signing/submit").json();
        assertThat(submits).hasSize(1);
        JsonNode entry = submits.get(0);
        assertThat(entry.path("protocol").asString()).isEqualTo("ca");
        assertThat(entry.path("refId").asString()).isNotBlank();
        assertThat(entry.path("httpStatus").asInt()).isEqualTo(200);
        assertThat(entry.path("request").asString()).contains("\"mode\":\"PUSH\"").contains("statementDigest");
        assertThat(entry.path("response").asString()).contains("AWAITING_SIGNER");
        // 医保的 infno 口径只看 chs 报文
        assertThat(client.get("/mock/requests?infno=9001").json()).isEmpty();
    }

    @Test
    void rootAndTimestampCertificatesAreExportedAsPem() {
        String root = client.get("/mock/ca/root").body();
        assertThat(root).startsWith("-----BEGIN CERTIFICATE-----").contains("-----END CERTIFICATE-----");
        X509Certificate rootCert = CaCrypto.certificate(root.getBytes());
        assertThat(rootCert.getSubjectX500Principal().getName()).contains("RHN Mock CA Root");
        assertThat(rootCert.getBasicConstraints()).isGreaterThanOrEqualTo(0);
        assertThat(CaCrypto.validAt(rootCert, java.time.Instant.now())).isTrue();
        X509Certificate tsa = CaCrypto.certificate(client.get("/mock/ca/tsa").body().getBytes());
        assertThat(CaCrypto.issuedBy(tsa, rootCert)).isTrue();
    }

    @Test
    void rootCertificateIsStableAcrossResets() {
        String before = client.get("/mock/ca/root").body();
        client.reset();
        assertThat(client.get("/mock/ca/root").body()).isEqualTo(before);
    }

    @Test
    void issuedSignerMaterialMatchesCertificateAndPrivateKey() {
        Result issued = client.issueSigner("王药师", 88, "NORMAL");
        assertThat(issued.status()).isEqualTo(200);
        assertThat(issued.json().path("externalSignerId").asString()).isEqualTo("mock-88");
        assertThat(client.issueSigner("王药师", 88, "NORMAL").status()).isEqualTo(409);
        assertThat(client.issueSigner("x", 89, "NOPE").status()).isEqualTo(500);

        JsonNode material = client.material("mock-88").json();
        X509Certificate certificate = CaCrypto.certificate(material.path("certificatePem").asString().getBytes());
        PrivateKey key = CaCrypto.privateKey(CaCrypto.fromPem(material.path("privateKeyPem").asString()));
        byte[] data = "hello".getBytes();
        assertThat(CaCrypto.verify(certificate.getPublicKey(), data, CaCrypto.sign(key, data))).isTrue();
        assertThat(CaCrypto.issuedBy(certificate, client.rootCertificate())).isTrue();
        assertThat(material.path("fingerprint").asString()).isEqualTo(CaCrypto.fingerprint(certificate));
        // 业务接口查询到的是同一张证书
        assertThat(client.call("signers/query", client.obj().put("externalSignerId", "mock-88"))
                .data().path("certificate").asString()).isEqualTo(material.path("certificateBase64").asString());
        assertThat(client.material("mock-404").status()).isEqualTo(404);
    }

    @Test
    void revokeMarksSignerAndRejectsUnissuedOnes() {
        client.issueSigner("a", 90, "NORMAL");
        JsonNode revoked = client.adminPost("/mock/ca/signers/mock-90/revoke", "{\"reason\":\"KEY_COMPROMISE\"}").json();
        assertThat(revoked.path("revoked").asBoolean()).isTrue();
        assertThat(revoked.path("state").asString()).isEqualTo("DISABLED");

        client.issueSigner("b", 91, "REJECTED");
        assertThat(client.adminPost("/mock/ca/signers/mock-91/revoke", null).status()).isEqualTo(409);
        assertThat(client.adminPost("/mock/ca/signers/mock-404/revoke", null).status()).isEqualTo(404);
    }

    @Test
    void clientSignRecordsLocalUkeySignatures() {
        client.issueSigner("a", 92, "NORMAL");
        Result signed = client.clientSign("mock-92", CaCrypto.b64("payload".getBytes()));
        assertThat(signed.status()).isEqualTo(200);
        JsonNode log = client.get("/mock/ca/client-signs").json();
        assertThat(log).hasSize(1);
        assertThat(log.get(0).path("externalSignerId").asString()).isEqualTo("mock-92");
        assertThat(client.clientSign("mock-92", "").status()).isIn(400, 409);
        assertThat(client.adminPost("/mock/ca/signers/mock-92/client-sign", "{}").status()).isEqualTo(400);
    }
}
