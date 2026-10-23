package com.rhn.mock.protocol.fiscal.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** 按 RHN FiscalModels 请求字段手写的财政票据测试客户端（未来 RHN HTTP 客户端的请求形状）。 */
public class FiscalTestClient {
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final String baseUrl;
    private int sequence;

    public FiscalTestClient(int port) {
        this.baseUrl = "http://127.0.0.1:" + port;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** 一条 100.00 元的开具请求：两行明细 60.00 + 40.00，医保 70 / 个账 10 / 患者 20 / 其他 0。 */
    public ObjectNode issueBody(String requestNo, String key, String payerName) {
        ObjectNode body = mapper.createObjectNode();
        body.put("receiptId", 1L);
        body.put("settlementId", 2L);
        body.put("receiptRequestNo", requestNo);
        body.put("idempotencyKey", key);
        body.put("receiptType", "MEDICAL_E_INVOICE");
        body.put("issueChannel", "FISCAL");
        body.put("fiscalAuthorityCode", "FISCAL");
        body.put("payerName", payerName);
        body.put("payerIdentityDigest", "digest");
        body.put("totalAmount", new BigDecimal("100.00"));
        body.put("roundingAmount", BigDecimal.ZERO);
        body.put("insuranceAmount", new BigDecimal("70.00"));
        body.put("personalAccountAmount", new BigDecimal("10.00"));
        body.put("patientAmount", new BigDecimal("20.00"));
        body.put("otherFundAmount", BigDecimal.ZERO);
        body.put("currencyCode", "CNY");
        ArrayNode items = body.putArray("items");
        item(items, 1, "01", "X1", "西药费", "60.00");
        item(items, 2, "02", "Z1", "检查费", "40.00");
        body.put("correlationId", "corr");
        return body;
    }

    private static void item(ArrayNode items, int line, String category, String code, String name, String amount) {
        ObjectNode item = items.addObject();
        item.put("lineNo", line);
        item.put("categoryCode", category);
        item.put("itemCode", code);
        item.put("itemName", name);
        item.put("quantity", BigDecimal.ONE);
        item.put("amount", new BigDecimal(amount));
    }

    public ObjectNode issueBody(String payerName) {
        int n = ++sequence;
        return issueBody("RCP-" + n, "IDEM-" + n, payerName);
    }

    public Result issue(ObjectNode body) {
        return op("issue", body);
    }

    public Result query(String requestNo, String externalNo) {
        ObjectNode body = mapper.createObjectNode();
        if (requestNo != null) body.put("receiptRequestNo", requestNo);
        if (externalNo != null) body.put("externalReceiptNo", externalNo);
        body.put("correlationId", "corr");
        return op("query", body);
    }

    public Result voidReceipt(String externalNo, String key) {
        ObjectNode body = mapper.createObjectNode();
        body.put("receiptId", 1L);
        body.put("receiptRequestNo", "RCP-V");
        body.put("idempotencyKey", key);
        body.put("externalReceiptNo", externalNo);
        body.put("reason", "作废测试");
        body.put("correlationId", "corr");
        return op("void", body);
    }

    public Result redFlush(String originalNo, String key) {
        ObjectNode body = mapper.createObjectNode();
        body.put("receiptId", 3L);
        body.put("receiptRequestNo", "RCP-RED-" + key);
        body.put("idempotencyKey", key);
        body.put("originalExternalReceiptNo", originalNo);
        body.putNull("originalFiscalCode");
        body.putNull("originalFiscalNumber");
        body.put("reason", "冲红测试");
        body.put("correlationId", "corr");
        return op("redFlush", body);
    }

    public Result op(String op, ObjectNode body) {
        return post("/fiscal/v1/receipts/" + op, mapper.writeValueAsString(body), "mock-fiscal-client", "mock-fiscal-secret");
    }

    public Result post(String path, String body, String clientId, String secret) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (clientId != null) builder.header("X-Client-Id", clientId);
        if (secret != null) builder.header("X-Client-Secret", secret);
        return send(builder.build());
    }

    public Result get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
    }

    public Result postAdmin(String path, String body) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    public Result putConfig(String body) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/mock/config?protocol=fiscal"))
                .header("Content-Type", "application/json;charset=UTF-8")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    public void reset() {
        postAdmin("/mock/reset", "{}");
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
            return new Result(response.statusCode(), response.body(), json);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("调用 Mock 服务失败: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 Mock 服务被中断", exception);
        }
    }

    public record Result(int status, String body, JsonNode json) {
        public JsonNode node() {
            return json == null ? MissingNode.getInstance() : json;
        }

        public boolean success() {
            return node().path("success").asBoolean(false);
        }

        public String text(String field) {
            return node().path(field).asString();
        }
    }
}
