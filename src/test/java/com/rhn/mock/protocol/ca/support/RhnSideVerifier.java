package com.rhn.mock.protocol.ca.support;

import com.rhn.mock.protocol.ca.CaCrypto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Optional;

/**
 * 复刻 RHN {@code PersonalSignatureVerifier} / {@code DefaultCaLoginService} 的本地校验：
 * 仅使用导出的根证书 / 时间戳证书与 SM2 原语，不调用 Mock 的任何校验接口，证明 Mock 签出的材料可被独立验过。
 */
public final class RhnSideVerifier {
    private RhnSideVerifier() {
    }

    /** 返回 null 表示通过，否则是 RHN 的失败码。 */
    public static String verifyCertificate(X509Certificate root, X509Certificate certificate, Instant at) {
        if (!CaCrypto.issuedBy(certificate, root) || !CaCrypto.validAt(root, at)) return "CA_CERTIFICATE_UNTRUSTED";
        if (!CaCrypto.validAt(certificate, at)) return "CA_CERTIFICATE_EXPIRED";
        if (!CaCrypto.allowsSigning(certificate)) return "CA_CERTIFICATE_USAGE_INVALID";
        return null;
    }

    /** 证书登录：对 toBeSigned 的签名 + 证书链。 */
    public static String verifyLogin(X509Certificate root, String certificateBase64, String toBeSignedBase64,
                                     String signatureBase64) {
        X509Certificate certificate = CaCrypto.certificate(certificateBase64);
        String failure = verifyCertificate(root, certificate, Instant.now());
        if (failure != null) return failure;
        return CaCrypto.verify(certificate.getPublicKey(), CaCrypto.fromB64(toBeSignedBase64),
                CaCrypto.fromB64(signatureBase64)) ? null : "CA_LOGIN_SIGNATURE_INVALID";
    }

    /** SM2_ENVELOPE 个人签名：证书链 + 封装内嵌摘要 + 签名 + 可选时间戳。 */
    public static String verifyEnvelopeSignature(X509Certificate root, X509Certificate tsa, JsonNode fetched,
                                                 String vendorCode, String digestAlgorithm, byte[] statement) {
        X509Certificate certificate = CaCrypto.certificate(fetched.path("certificate").asString());
        byte[] signature = CaCrypto.fromB64(fetched.path("signatureValue").asString());
        Instant signedAt = Instant.now();
        String token = fetched.path("timestampToken").asString();
        if (token != null && !token.isBlank()) {
            Optional<Instant> time = verifyTimestamp(tsa, token, signature);
            if (time.isEmpty()) return "CA_TIMESTAMP_INVALID";
            signedAt = time.get();
        }
        String failure = verifyCertificate(root, certificate, signedAt);
        if (failure != null) return failure;
        String envelope = fetched.path("signedEnvelope").asString();
        if (!envelopeBinds(envelope, vendorCode, digestAlgorithm, CaCrypto.digest(digestAlgorithm, statement))
                || !CaCrypto.verify(certificate.getPublicKey(), envelope.getBytes(StandardCharsets.UTF_8), signature)) {
            return "CA_SIGNATURE_INVALID";
        }
        return null;
    }

    public static boolean envelopeBinds(String envelope, String vendorCode, String digestAlgorithm, byte[] digest) {
        try {
            JsonNode node = JsonMapper.builder().build().readTree(envelope);
            return vendorCode.equals(node.get("vendor").asString())
                    && digestAlgorithm.equalsIgnoreCase(node.get("digestAlgorithm").asString())
                    && MessageDigest.isEqual(digest, CaCrypto.fromB64(node.get("digest").asString()));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static Optional<Instant> verifyTimestamp(X509Certificate tsa, String token, byte[] signatureValue) {
        try {
            String[] parts = token.split("\\.");
            byte[] payload = CaCrypto.fromB64(parts[0]);
            if (!CaCrypto.verify(tsa.getPublicKey(), payload, CaCrypto.fromB64(parts[1]))) return Optional.empty();
            JsonNode node = JsonMapper.builder().build().readTree(new String(payload, StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(CaCrypto.digest(CaCrypto.SM3, signatureValue),
                    CaCrypto.fromB64(node.get("digest").asString()))) return Optional.empty();
            return Optional.of(Instant.parse(node.get("time").asString()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }
}
