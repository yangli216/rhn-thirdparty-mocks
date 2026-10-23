package com.rhn.mock.protocol.alipay;

import com.rhn.mock.protocol.alipay.config.AlipayMockProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支付宝 RSA2 加签/验签。
 *
 * <p><b>验签原文口径由真实 SDK 实测确定</b>（alipay-sdk-java 4.40.1036.ALL）：key 升序、
 * {@code k=v} 以 {@code &} 连接、跳过空值，且**只排除 {@code sign}**——
 * {@code sign_type}/{@code alipay_sdk}/{@code format} 等协议参数都在签名范围内。
 * 这与 {@code AbstractAlipayClient#getSignatureContent} 的行为一致。
 *
 * <p>响应加签口径：对发出的 {@code *_response} 内层 JSON 原文逐字节加签。
 */
@Component
public class AlipaySigner {
    private final AlipayMockProperties properties;
    private final PrivateKey responsePrivateKey;
    private final PublicKey requestPublicKey;

    public AlipaySigner(AlipayMockProperties properties) {
        this.properties = properties;
        this.responsePrivateKey = privateKey(properties.responseSignPrivateKey());
        this.requestPublicKey = publicKey(properties.requestVerifyPublicKey());
    }

    /** 对响应内层 JSON 原文加签，返回 base64 签名。 */
    public String sign(String content) {
        try {
            Signature signature = Signature.getInstance(algorithm(properties.signType()));
            signature.initSign(responsePrivateKey);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception exception) {
            throw new IllegalStateException("支付宝响应加签失败", exception);
        }
    }

    /** 校验请求签名；签名缺失、不可解析或不匹配都返回 false。 */
    public boolean verify(Map<String, String> params, String sign, String signType) {
        if (sign == null || sign.isBlank()) return false;
        try {
            String type = signType == null || signType.isBlank() ? properties.signType() : signType;
            Signature signature = Signature.getInstance(algorithm(type));
            signature.initVerify(requestPublicKey);
            signature.update(signContent(params).getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(sign));
        } catch (Exception exception) {
            return false;
        }
    }

    /** 请求验签原文：升序拼接、跳过空值、仅排除 {@code sign}。 */
    public static String signContent(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        new TreeMap<>(params).forEach((key, value) -> {
            if (key == null || key.isBlank() || "sign".equals(key) || value == null || value.isBlank()) return;
            if (!builder.isEmpty()) builder.append('&');
            builder.append(key).append('=').append(value);
        });
        return builder.toString();
    }

    private static String algorithm(String signType) {
        return "RSA".equalsIgnoreCase(signType) ? "SHA1withRSA" : "SHA256withRSA";
    }

    private static PrivateKey privateKey(String location) {
        try {
            byte[] encoded = Base64.getDecoder().decode(pemBody(location, "PRIVATE KEY"));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载支付宝 Mock 响应私钥: " + location, exception);
        }
    }

    private static PublicKey publicKey(String location) {
        try {
            byte[] encoded = Base64.getDecoder().decode(pemBody(location, "PUBLIC KEY"));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载支付宝 Mock 请求验签公钥: " + location, exception);
        }
    }

    private static String pemBody(String location, String type) throws IOException {
        ResourceLoader loader = new DefaultResourceLoader();
        Resource resource = loader.getResource(location);
        String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return text.replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
    }
}
