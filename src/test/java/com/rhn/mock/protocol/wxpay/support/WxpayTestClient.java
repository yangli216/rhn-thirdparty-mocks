package com.rhn.mock.protocol.wxpay.support;

import com.rhn.mock.protocol.wxpay.WxpayXml;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 按 WxJava 4.8.0 发出的报文形状手工组包的测试客户端，不依赖 wxpay SDK。
 *
 * <p>与 SDK 一致的三个细节：根元素必须是 {@code <xml>}；请求不带 {@code sign_type}（rhN 未设置
 * sign type ⇒ WxJava 不写该字段）；MD5 签名 = 升序拼接、跳过空值、排除 {@code sign}/{@code key}，
 * 追加 {@code &key=<apiKey>} 后转大写。
 */
public class WxpayTestClient {
    public static final String APP_ID = "wxmockappid00001";
    public static final String MCH_ID = "1900000109";
    public static final String API_KEY = "0123456789abcdef0123456789abcdef";

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;

    public WxpayTestClient(int port) {
        this.baseUrl = "http://127.0.0.1:" + port + "/wxpay";
    }

    // ==================== 业务调用 ====================

    public Result micropay(String outTradeNo, String totalFee, String authCode) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("appid", APP_ID);
        fields.put("mch_id", MCH_ID);
        fields.put("nonce_str", nonce());
        fields.put("body", "医疗费用");
        fields.put("out_trade_no", outTradeNo);
        fields.put("total_fee", totalFee);
        fields.put("spbill_create_ip", "127.0.0.1");
        fields.put("auth_code", authCode);
        fields.put("fee_type", "CNY");
        return post("/pay/micropay", signed(fields));
    }

    public Result orderQuery(String outTradeNo) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("appid", APP_ID);
        fields.put("mch_id", MCH_ID);
        fields.put("nonce_str", nonce());
        fields.put("out_trade_no", outTradeNo);
        return post("/pay/orderquery", signed(fields));
    }

    public Result refund(String transactionId, String outRefundNo, String refundFee, String totalFee) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("appid", APP_ID);
        fields.put("mch_id", MCH_ID);
        fields.put("nonce_str", nonce());
        fields.put("transaction_id", transactionId);
        fields.put("out_refund_no", outRefundNo);
        fields.put("refund_fee", refundFee);
        fields.put("total_fee", totalFee);
        fields.put("refund_fee_type", "CNY");
        return post("/secapi/pay/refundv2", signed(fields));
    }

    public Result refundQuery(String outRefundNo) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("appid", APP_ID);
        fields.put("mch_id", MCH_ID);
        fields.put("nonce_str", nonce());
        fields.put("out_refund_no", outRefundNo);
        return post("/pay/refundquery", signed(fields));
    }

    /** 对账单下载，形状同 WxJava downloadRawBill（bill_type=ALL）。 */
    public Result downloadBill(String billDate) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("appid", APP_ID);
        fields.put("mch_id", MCH_ID);
        fields.put("nonce_str", nonce());
        fields.put("bill_date", billDate);
        fields.put("bill_type", "ALL");
        return post("/pay/downloadbill", signed(fields));
    }

    /** 用错误签名发一次请求，用于验证 Mock 拒绝坏签名。 */
    public Result micropayWithBadSignature(String outTradeNo, String totalFee) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("appid", APP_ID);
        fields.put("mch_id", MCH_ID);
        fields.put("nonce_str", nonce());
        fields.put("out_trade_no", outTradeNo);
        fields.put("total_fee", totalFee);
        fields.put("auth_code", "134567890123456789");
        fields.put("sign", "0000000000000000000000000000DEAD");
        return post("/pay/micropay", WxpayXml.build(fields));
    }

    /** 直接发送未签名/自定义 XML，用于负向用例。 */
    public Result postRaw(String path, String xml) {
        return post(path, xml);
    }

    // ==================== 管理端点 ====================

    public void reset() {
        try {
            http.send(HttpRequest.newBuilder(URI.create(host() + "/mock/reset"))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException("调用 Mock reset 失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 Mock reset 被中断", exception);
        }
    }

    // ==================== 组包与传输 ====================

    /** 计算 MD5 签名并写入 {@code sign}，返回 XML。 */
    public String signed(Map<String, String> fields) {
        fields.put("sign", sign(fields));
        return WxpayXml.build(fields);
    }

    /** 签名与 WxJava {@code SignUtils.createSign} 同口径。 */
    public static String sign(Map<String, String> fields) {
        return md5(signContent(fields) + "&key=" + API_KEY);
    }

    public static String signContent(Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        new TreeMap<>(fields).forEach((key, value) -> {
            if (key == null || key.isBlank() || "sign".equals(key) || "key".equals(key) || value == null || value.isBlank()) return;
            if (!builder.isEmpty()) builder.append('&');
            builder.append(key).append('=').append(value);
        });
        return builder.toString();
    }

    private Result post(String path, String xml) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "text/xml;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Result(response.statusCode(), response.body(), WxpayXml.parse(response.body()));
        } catch (IOException exception) {
            throw new IllegalStateException("调用 Mock 服务失败: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 Mock 服务被中断", exception);
        }
    }

    private String host() {
        return baseUrl.substring(0, baseUrl.indexOf("/wxpay"));
    }

    private static String nonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String md5(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) builder.append(String.format("%02X", value));
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("测试客户端 MD5 失败", exception);
        }
    }

    /** 一次网关调用结果；{@code fields} 为响应 {@code <xml>} 解析结果。 */
    public record Result(int status, String body, Map<String, String> fields) {
        public String value(String name) {
            return fields.get(name);
        }

        /** 按 apiKey 校验响应签名（口径同 WxJava）。 */
        public boolean signValid() {
            String sign = fields.get("sign");
            return sign != null && sign.equalsIgnoreCase(sign(fields));
        }
    }
}
