package com.rhn.mock.protocol.ca.support;

import com.rhn.mock.protocol.ca.CaCrypto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按 docs/ca-mock-protocol.md 手工组包的 CA 测试客户端，扮演 RHN 侧的厂商适配器，
 * 不依赖 rhn 模块；管理端点（{@code /mock/**}）一并封装。
 */
public class CaTestClient {
    public static final String CLIENT_ID = "mock-ca-client";
    public static final String CLIENT_SECRET = "mock-ca-secret";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final AtomicLong signingOrderIds = new AtomicLong(1000);
    private final String baseUrl;

    public CaTestClient(int port) {
        this.baseUrl = "http://127.0.0.1:" + port;
    }

    // ==================== 业务操作 ====================

    public Result call(String op, ObjectNode body) {
        return post("/ca/v1/" + op, body == null ? "{}" : write(body), CLIENT_ID, CLIENT_SECRET);
    }

    public Result callRaw(String op, String rawBody) {
        return post("/ca/v1/" + op, rawBody, CLIENT_ID, CLIENT_SECRET);
    }

    public Result post(String path, String rawBody, String clientId, String clientSecret) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json;charset=UTF-8");
        if (clientId != null) builder.header("X-Client-Id", clientId);
        if (clientSecret != null) builder.header("X-Client-Secret", clientSecret);
        return send(builder.POST(HttpRequest.BodyPublishers.ofString(rawBody)).build());
    }

    public ObjectNode obj() {
        return mapper.createObjectNode();
    }

    public Result enroll(String name, String identityNumber, long userId) {
        ObjectNode body = obj();
        body.put("userId", userId);
        body.put("practitionerId", userId + 5000);
        body.put("name", name);
        body.put("identityType", "ID_CARD");
        body.put("identityNumber", identityNumber);
        body.put("mobile", "13800001234");
        body.put("department", "内科");
        return call("signers/enroll", body);
    }

    /** 备案一个 NORMAL 人员并返回其外部签名人号。 */
    public String enrollNormal(String name, long userId) {
        Result result = enroll(name, "110101199001010011", userId);
        return result.data().path("externalSignerId").asString();
    }

    public Result submit(long signingOrderId, String signerId, String mode, byte[] statement) {
        ObjectNode body = obj();
        body.put("tenantId", 1);
        body.put("organizationId", 1);
        body.put("signingOrderId", signingOrderId);
        if (signerId != null) body.put("externalSignerId", signerId);
        body.put("mode", mode);
        body.put("sceneCode", "OUTPATIENT_PRESCRIPTION");
        body.put("digestAlgorithm", "SM3");
        body.put("statementDigest", CaCrypto.b64(CaCrypto.digest("SM3", statement)));
        body.put("subject", "门诊处方 RX2026");
        body.put("patientDisplay", "张*");
        body.put("expiresAt", Instant.now().plusSeconds(3600).toString());
        return call("signing/submit", body);
    }

    public long nextSigningOrderId() {
        return signingOrderIds.incrementAndGet();
    }

    public Result query(String externalOrderId) {
        return call("signing/query", obj().put("externalOrderId", externalOrderId));
    }

    public Result fetch(String externalOrderId) {
        return call("signing/fetch", obj().put("externalOrderId", externalOrderId));
    }

    // ==================== 管理端点 ====================

    public Result get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
    }

    public Result adminPost(String path, String body) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body)).build());
    }

    public void reset() {
        adminPost("/mock/reset", "{}");
    }

    public Result putConfig(String body) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/mock/config?protocol=ca"))
                .header("Content-Type", "application/json;charset=UTF-8")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    public Result issueSigner(String name, long userId, String scenario) {
        return adminPost("/mock/ca/signers", "{\"name\":\"" + name + "\",\"userId\":" + userId
                + ",\"scenario\":\"" + scenario + "\"}");
    }

    public Result material(String signerId) {
        return get("/mock/ca/signers/" + signerId + "/material");
    }

    /** 模拟 UKey：对 Base64 待签字节本地签名，返回 {signatureValue, certificate}。 */
    public Result clientSign(String signerId, String dataBase64) {
        return adminPost("/mock/ca/signers/" + signerId + "/client-sign", "{\"data\":\"" + dataBase64 + "\"}");
    }

    public X509Certificate rootCertificate() {
        return CaCrypto.certificate(CaCrypto.fromPem(get("/mock/ca/root").body()));
    }

    // ==================== 传输 ====================

    public String write(JsonNode node) {
        return mapper.writeValueAsString(node);
    }

    public Result send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode json = null;
            try {
                json = mapper.readTree(response.body());
            } catch (RuntimeException ignored) {
                // 非 JSON 回执保持 null
            }
            return new Result(response.statusCode(), response.body(), json);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("调用 Mock 服务失败: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 Mock 服务被中断", exception);
        }
    }

    /** 一次调用的结果；json 为 null 表示回执不是 JSON。 */
    public record Result(int status, String body, JsonNode json) {
        public int code() {
            return json == null || !json.has("code") ? Integer.MIN_VALUE : json.path("code").asInt();
        }

        public boolean succeeded() {
            return status == 200 && code() == 0;
        }

        public JsonNode data() {
            return json == null ? MissingNode.getInstance() : json.path("data");
        }

        public String errorCode() {
            return json == null ? null : json.path("errorCode").asString();
        }
    }
}
