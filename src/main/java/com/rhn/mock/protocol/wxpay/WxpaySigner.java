package com.rhn.mock.protocol.wxpay;

import com.rhn.mock.protocol.wxpay.config.WxpayMockProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 微信支付 API v2 MD5 加签/验签。
 *
 * <p>口径与 WxJava {@code SignUtils.createSign} 一致（NO_SIGN_PARAMS = sign/key/xmlString/xmlDoc/couponList）：
 * key 升序、拼 {@code k=v&…}、跳过空值、排除 {@code sign} 与 {@code key}，
 * 末尾追加 {@code &key=<apiKey>}，MD5 后转大写十六进制。
 *
 * <p>请求与响应用同一份密钥；响应签名的原文就是 Mock 自己发出的元素集，因此与 WxJava 的验签天然一致。
 */
@Component
public class WxpaySigner {
    private final WxpayMockProperties properties;

    public WxpaySigner(WxpayMockProperties properties) {
        this.properties = properties;
    }

    public String sign(Map<String, String> fields) {
        return md5(signContent(fields) + "&key=" + properties.apiKey());
    }

    public boolean verify(Map<String, String> fields, String sign) {
        if (sign == null || sign.isBlank()) return false;
        return sign.equalsIgnoreCase(sign(fields));
    }

    /** 签名原文：升序拼接、跳过空值，排除 {@code sign} 与 {@code key}（不含 {@code &key=} 尾巴）。 */
    public static String signContent(Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        new TreeMap<>(fields).forEach((key, value) -> {
            if (key == null || key.isBlank() || isExcluded(key) || value == null || value.isBlank()) return;
            if (!builder.isEmpty()) builder.append('&');
            builder.append(key).append('=').append(value);
        });
        return builder.toString();
    }

    private static boolean isExcluded(String key) {
        return "sign".equals(key) || "key".equals(key);
    }

    private static String md5(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(content.getBytes(StandardCharsets.UTF_8));
            List<String> hex = new ArrayList<>(digest.length);
            for (byte value : digest) hex.add(String.format("%02X", value));
            return String.join("", hex);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 MD5 实现", exception);
        }
    }
}
