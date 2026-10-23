package com.rhn.mock.protocol.ca;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

/**
 * SM2/SM3 原语，口径与 RHN 后端 {@code SmCrypto} 一致：未注册的 BouncyCastle Provider 实例、
 * SM3withSM2（DER 签名、缺省 userId 1234567812345678）、证书指纹 = DER 的 SM3 大写十六进制。
 */
public final class CaCrypto {
    public static final String SM3 = "SM3";
    public static final String SHA_256 = "SHA-256";
    private static final String SM2_SIGNATURE = "SM3withSM2";
    private static final Provider BC = new BouncyCastleProvider();
    private static final SecureRandom RANDOM = new SecureRandom();

    private CaCrypto() {
    }

    public static boolean supportsDigest(String algorithm) {
        if (algorithm == null) return false;
        String value = algorithm.trim().toUpperCase();
        return SM3.equals(value) || SHA_256.equals(value) || "SHA256".equals(value);
    }

    public static byte[] digest(String algorithm, byte[] content) {
        if (!supportsDigest(algorithm)) throw new IllegalArgumentException("不支持的摘要算法: " + algorithm);
        try {
            MessageDigest digest = SM3.equals(algorithm.trim().toUpperCase())
                    ? MessageDigest.getInstance(SM3, BC) : MessageDigest.getInstance(SHA_256);
            return digest.digest(content);
        } catch (Exception exception) {
            throw new IllegalStateException("摘要计算失败", exception);
        }
    }

    public static int digestLength(String algorithm) {
        return 32;
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", BC);
            generator.initialize(new ECGenParameterSpec("sm2p256v1"), RANDOM);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException("生成 SM2 密钥对失败", exception);
        }
    }

    public static byte[] sign(PrivateKey key, byte[] data) {
        try {
            Signature signature = Signature.getInstance(SM2_SIGNATURE, BC);
            signature.initSign(key, RANDOM);
            signature.update(data);
            return signature.sign();
        } catch (Exception exception) {
            throw new IllegalStateException("SM2 签名失败", exception);
        }
    }

    public static boolean verify(java.security.PublicKey key, byte[] data, byte[] signatureValue) {
        try {
            Signature signature = Signature.getInstance(SM2_SIGNATURE, BC);
            signature.initVerify(key);
            signature.update(data);
            return signature.verify(signatureValue);
        } catch (Exception exception) {
            return false;
        }
    }

    public static PrivateKey privateKey(byte[] pkcs8) {
        try {
            return KeyFactory.getInstance("EC", BC).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception exception) {
            throw new IllegalArgumentException("无效的 SM2 私钥", exception);
        }
    }

    public static X509Certificate certificate(byte[] der) {
        try {
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509", BC)
                    .generateCertificate(new ByteArrayInputStream(der));
            if (certificate == null) throw new IllegalArgumentException("空的 X.509 证书");
            return certificate;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无效的 X.509 证书", exception);
        }
    }

    public static X509Certificate certificate(String base64Der) {
        return certificate(Base64.getMimeDecoder().decode(base64Der));
    }

    public static byte[] encoded(X509Certificate certificate) {
        try {
            return certificate.getEncoded();
        } catch (Exception exception) {
            throw new IllegalArgumentException("证书编码失败", exception);
        }
    }

    public static String fingerprint(X509Certificate certificate) {
        return HexFormat.of().withUpperCase().formatHex(digest(SM3, encoded(certificate)));
    }

    public static boolean issuedBy(X509Certificate certificate, X509Certificate issuer) {
        try {
            if (!certificate.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) return false;
            certificate.verify(issuer.getPublicKey(), BC);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    public static boolean validAt(X509Certificate certificate, Instant instant) {
        try {
            certificate.checkValidity(Date.from(instant));
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    public static boolean allowsSigning(X509Certificate certificate) {
        boolean[] usage = certificate.getKeyUsage();
        return usage != null && (usage[0] || usage[1]);
    }

    public static X509Certificate issue(String subjectDn, java.security.PublicKey subjectKey, String issuerDn,
                                        PrivateKey issuerKey, Instant notBefore, Instant notAfter, boolean authority) {
        return issue(subjectDn, subjectKey, new X500Name(issuerDn), issuerKey, notBefore, notAfter, authority);
    }

    /** 以签发者证书的主体原样作为 issuer，保证 DN 编码与链校验一致。 */
    public static X509Certificate issue(String subjectDn, java.security.PublicKey subjectKey, X509Certificate issuer,
                                        PrivateKey issuerKey, Instant notBefore, Instant notAfter, boolean authority) {
        return issue(subjectDn, subjectKey, X500Name.getInstance(issuer.getSubjectX500Principal().getEncoded()),
                issuerKey, notBefore, notAfter, authority);
    }

    private static X509Certificate issue(String subjectDn, java.security.PublicKey subjectKey, X500Name issuer,
                                         PrivateKey issuerKey, Instant notBefore, Instant notAfter, boolean authority) {
        try {
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer,
                    new BigInteger(127, RANDOM), Date.from(notBefore), Date.from(notAfter),
                    new X500Name(subjectDn), subjectKey);
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(authority));
            builder.addExtension(Extension.keyUsage, true, authority
                    ? new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign)
                    : new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
            var signer = new JcaContentSignerBuilder(SM2_SIGNATURE).setProvider(BC).build(issuerKey);
            return new JcaX509CertificateConverter().setProvider(BC).getCertificate(builder.build(signer));
        } catch (Exception exception) {
            throw new IllegalStateException("签发 SM2 证书失败", exception);
        }
    }

    public static String b64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    public static byte[] fromB64(String value) {
        return Base64.getMimeDecoder().decode(value);
    }

    public static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    /** 解出 PEM 中的 DER；不带 PEM 头的纯 Base64 也接受。 */
    public static byte[] fromPem(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END)[A-Z ]+-----", "").replaceAll("\\s+", "");
        return Base64.getDecoder().decode(body);
    }
}
