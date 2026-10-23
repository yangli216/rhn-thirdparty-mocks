package com.rhn.mock.protocol.alipay.support;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * 按真实 {@code DefaultAlipayClient} 发出的报文形状手工组包的测试客户端，不依赖 alipay SDK。
 *
 * <p>与 SDK 一致的三个细节：协议参数放 query、{@code biz_content} 放 form body；
 * 签名原文为"升序拼接、跳过空值、仅排除 {@code sign}"；响应验签对象是发出的内层 JSON 原文。
 */
public class AlipayTestClient {
    public static final String SIGN_TYPE = "RSA2";
    private static final String CHARSET = "UTF-8";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final PrivateKey appPrivateKey;
    private final PublicKey alipayPublicKey;
    private final String gatewayUrl;
    private final String baseUrl;
    private final String appId;

    public AlipayTestClient(int port) {
        this(port, "mock-alipay-app");
    }

    public AlipayTestClient(int port, String appId) {
        this.baseUrl = "http://127.0.0.1:" + port;
        this.gatewayUrl = baseUrl + "/alipay/gateway.do";
        this.appId = appId;
        this.appPrivateKey = privateKey("classpath:mock/alipay/app-private-key.pem");
        this.alipayPublicKey = publicKey("classpath:mock/alipay/alipay-public-key.pem");
    }

    // ==================== 交易 ====================

    public ObjectNode newBiz() {
        return mapper.createObjectNode();
    }

    public Result pay(String outTradeNo, String totalAmount, String authCode) {
        ObjectNode biz = mapper.createObjectNode();
        biz.put("out_trade_no", outTradeNo);
        biz.put("scene", "bar_code");
        biz.put("auth_code", authCode);
        biz.put("subject", "医疗费用");
        biz.put("seller_id", "2088mock");
        biz.put("total_amount", totalAmount);
        return call("alipay.trade.pay", biz);
    }

    public Result query(String outTradeNo) {
        ObjectNode biz = mapper.createObjectNode();
        biz.put("out_trade_no", outTradeNo);
        return call("alipay.trade.query", biz);
    }

    public Result refund(String tradeNo, String outRequestNo, String refundAmount) {
        ObjectNode biz = mapper.createObjectNode();
        biz.put("trade_no", tradeNo);
        biz.put("out_request_no", outRequestNo);
        biz.put("refund_amount", refundAmount);
        biz.put("refund_reason", "医疗费用退费");
        return call("alipay.trade.refund", biz);
    }

    public Result refundQuery(String tradeNo, String outRequestNo) {
        ObjectNode biz = mapper.createObjectNode();
        biz.put("trade_no", tradeNo);
        biz.put("out_request_no", outRequestNo);
        return call("alipay.trade.fastpay.refund.query", biz);
    }

    /** 对账单下载链接查询，形状同 RHN AlipayReconciliationAdapter（bill_type=trade, secure=true）。 */
    public Result billDownloadUrl(String billDate) {
        ObjectNode biz = mapper.createObjectNode();
        biz.put("bill_type", "trade");
        biz.put("bill_date", billDate);
        biz.put("secure", "true");
        return call("alipay.data.dataservice.bill.downloadurl.query", biz);
    }

    // ==================== 管理端点 ====================

    public void reset() {
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/mock/reset"))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
    }

    public JsonNode config() {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/mock/config?protocol=alipay")).GET().build()).json();
    }

    public JsonNode putConfig(String body) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/mock/config?protocol=alipay"))
                .header("Content-Type", "application/json;charset=UTF-8")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build()).json();
    }

    // ==================== 组包与传输 ====================

    public Result call(String method, ObjectNode biz) {
        Map<String, String> params = new TreeMap<>();
        params.put("app_id", appId);
        params.put("method", method);
        params.put("charset", CHARSET);
        params.put("sign_type", SIGN_TYPE);
        params.put("timestamp", LocalDateTime.now().format(TIMESTAMP));
        params.put("version", "1.0");
        params.put("format", "json");
        params.put("alipay_sdk", "alipay-sdk-java-4.40.1036.ALL");
        params.put("biz_content", mapper.writeValueAsString(biz));
        return transmit(params, sign(signContent(params)));
    }

    /** 用错误签名发一次请求，用于验证 Mock 拒绝坏签名。 */
    public Result callWithBadSignature(String method, ObjectNode biz) {
        Map<String, String> params = new TreeMap<>();
        params.put("app_id", appId);
        params.put("method", method);
        params.put("charset", CHARSET);
        params.put("sign_type", SIGN_TYPE);
        params.put("timestamp", LocalDateTime.now().format(TIMESTAMP));
        params.put("version", "1.0");
        params.put("format", "json");
        params.put("biz_content", mapper.writeValueAsString(biz));
        return transmit(params, sign("app_id=" + appId + "&tampered=true"));
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

    private Result transmit(Map<String, String> params, String sign) {
        StringBuilder query = new StringBuilder();
        params.forEach((key, value) -> {
            if (!"biz_content".equals(key)) append(query, key, value);
        });
        append(query, "sign", sign);
        String body = "biz_content=" + encode(params.get("biz_content"));
        return send(HttpRequest.newBuilder(URI.create(gatewayUrl + "?" + query))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
    }

    private String sign(String content) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(appPrivateKey);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception exception) {
            throw new IllegalStateException("测试客户端加签失败", exception);
        }
    }

    private boolean verify(String content, String sign) {
        if (sign == null || sign.isBlank()) return false;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(alipayPublicKey);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(sign));
        } catch (Exception exception) {
            return false;
        }
    }

    private static void append(StringBuilder builder, String key, String value) {
        if (!builder.isEmpty()) builder.append('&');
        builder.append(encode(key)).append('=').append(encode(value));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private Result send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = null;
            try {
                json = mapper.readTree(response.body());
            } catch (RuntimeException ignored) {
                // 非 JSON 回执保持 null
            }
            return new Result(response.statusCode(), response.body(), json, alipayPublicKey);
        } catch (IOException exception) {
            throw new IllegalStateException("调用 Mock 服务失败: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 Mock 服务被中断", exception);
        }
    }

    private static int matchingBrace(String text, int start) {
        int depth = 0;
        boolean quoted = false;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (quoted) {
                if (current == '\\') index++;
                else if (current == '"') quoted = false;
            } else if (current == '"') {
                quoted = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index + 1;
            }
        }
        return -1;
    }

    /** 一次网关调用结果；{@code json} 为 null 表示回执不是 JSON。 */
    public record Result(int status, String body, JsonNode json, PublicKey alipayPublicKey) {
        public JsonNode inner(String responseType) {
            return json == null ? MissingNode.getInstance() : json.path(responseType);
        }

        public String code(String responseType) {
            return inner(responseType).path("code").asString();
        }

        /**
         * 按 SDK 的 {@code getSignSourceData} 口径验签：签名对象必须是回执中
         * {@code "<responseType>":} 之后的那个 JSON 对象原文，而不是再序列化的结果。
         */
        public boolean signValid(String responseType) {
            if (json == null) return false;
            String marker = "\"" + responseType + "\"";
            int index = body.indexOf(marker);
            if (index <= 0) return false;
            int start = body.indexOf('{', index + marker.length());
            if (start < 0) return false;
            int end = matchingBrace(body, start);
            if (end < 0) return false;
            String content = body.substring(start, end);
            String sign = json.path("sign").asString();
            if (sign == null || sign.isBlank()) return false;
            try {
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initVerify(alipayPublicKey);
                signature.update(content.getBytes(StandardCharsets.UTF_8));
                return signature.verify(Base64.getDecoder().decode(sign));
            } catch (Exception exception) {
                return false;
            }
        }
    }

    private static PrivateKey privateKey(String location) {
        try {
            byte[] encoded = Base64.getDecoder().decode(pemBody(location, "PRIVATE KEY"));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载测试应用私钥: " + location, exception);
        }
    }

    private static PublicKey publicKey(String location) {
        try {
            byte[] encoded = Base64.getDecoder().decode(pemBody(location, "PUBLIC KEY"));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载测试支付宝公钥: " + location, exception);
        }
    }

    private static String pemBody(String location, String type) throws IOException {
        Resource resource = new DefaultResourceLoader().getResource(location);
        String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return text.replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
    }
}
