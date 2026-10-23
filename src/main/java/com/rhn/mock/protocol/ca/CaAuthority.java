package com.rhn.mock.protocol.ca;

import com.rhn.mock.protocol.ca.config.CaMockProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 测试 CA：固定根证书与时间戳服务（随包发布的 throwaway 材料），现签终端证书，
 * 签发 / 校验时间戳令牌。令牌格式 {@code base64(payloadJson) + "." + base64(SM2 签名)}，
 * payload 为 {@code {authority,time,digest}}，digest = SM3(被盖章的签名值)。
 */
@Component
public class CaAuthority {
    public static final String ORGANIZATION = "O=RHN MOCK ONLY";

    private final CaMockProperties properties;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final X509Certificate root;
    private final PrivateKey rootKey;
    private final X509Certificate tsaCertificate;
    private final PrivateKey tsaKey;

    public CaAuthority(CaMockProperties properties) {
        this.properties = properties;
        this.root = CaCrypto.certificate(read(properties.rootCert()));
        this.rootKey = CaCrypto.privateKey(CaCrypto.fromPem(new String(read(properties.rootKey()), StandardCharsets.UTF_8)));
        this.tsaCertificate = CaCrypto.certificate(read(properties.tsaCert()));
        this.tsaKey = CaCrypto.privateKey(CaCrypto.fromPem(new String(read(properties.tsaKey()), StandardCharsets.UTF_8)));
    }

    public String authorityName() {
        return properties.vendorCode() + "-tsa";
    }

    public X509Certificate root() {
        return root;
    }

    public X509Certificate tsaCertificate() {
        return tsaCertificate;
    }

    public String rootDn() {
        return root.getSubjectX500Principal().getName();
    }

    /** 现签一张终端证书：{@code validFrom/validTo} 由场景决定（过期证书用过去的区间）。 */
    public Issued issueSigner(String commonName, String serial, Instant notBefore, Instant notAfter) {
        KeyPair keys = CaCrypto.generateKeyPair();
        String cn = commonName == null || commonName.isBlank() ? "signer" : commonName.replaceAll("[,=+<>#;\"\\\\]", "");
        X509Certificate certificate = CaCrypto.issue("CN=" + cn + ",OU=" + serial + "," + ORGANIZATION,
                keys.getPublic(), root, rootKey, notBefore, notAfter, false);
        return new Issued(keys, certificate);
    }

    public Instant defaultNotBefore() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.HOURS);
    }

    public Instant defaultNotAfter() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(properties.signerValidityDays(), ChronoUnit.DAYS);
    }

    public boolean chainsToRoot(X509Certificate certificate) {
        return CaCrypto.issuedBy(certificate, root);
    }

    public Stamp stamp(byte[] signatureValue) {
        Instant time = Instant.now();
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("authority", authorityName());
        payload.put("time", time.toString());
        payload.put("digest", CaCrypto.b64(CaCrypto.digest(CaCrypto.SM3, signatureValue)));
        byte[] bytes = CaCrypto.utf8(mapper.writeValueAsString(payload));
        String token = CaCrypto.b64(bytes) + "." + CaCrypto.b64(CaCrypto.sign(tsaKey, bytes));
        return new Stamp(authorityName(), token, time);
    }

    /** 校验令牌覆盖的确是该签名值，返回盖章时间；令牌畸形 / 签名不符 / 数据不符均返回 empty。 */
    public Optional<Instant> verifyStamp(String authority, String token, byte[] signatureValue) {
        try {
            if (!authorityName().equals(authority)) return Optional.empty();
            String[] parts = token.split("\\.");
            byte[] payload = CaCrypto.fromB64(parts[0]);
            if (!CaCrypto.verify(tsaCertificate.getPublicKey(), payload, CaCrypto.fromB64(parts[1]))) {
                return Optional.empty();
            }
            JsonNode node = mapper.readTree(new String(payload, StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(CaCrypto.digest(CaCrypto.SM3, signatureValue),
                    CaCrypto.fromB64(node.get("digest").asString()))) {
                return Optional.empty();
            }
            return Optional.of(Instant.parse(node.get("time").asString()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static byte[] read(String location) {
        try {
            Resource resource = new DefaultResourceLoader().getResource(location);
            try (var stream = resource.getInputStream()) {
                return stream.readAllBytes();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("无法读取 CA 材料: " + location, exception);
        }
    }

    public record Issued(KeyPair keys, X509Certificate certificate) {
    }

    public record Stamp(String authority, String token, Instant time) {
    }
}
