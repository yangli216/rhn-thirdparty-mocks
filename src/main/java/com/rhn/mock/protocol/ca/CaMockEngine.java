package com.rhn.mock.protocol.ca;

import com.rhn.mock.core.ExchangeLog;
import com.rhn.mock.core.JsonNodes;
import com.rhn.mock.core.MockHttpResponse;
import com.rhn.mock.protocol.ca.CaScenarioCatalog.Behavior;
import com.rhn.mock.protocol.ca.config.CaMockConfig;
import com.rhn.mock.protocol.ca.config.CaMockConfigService;
import com.rhn.mock.protocol.ca.config.CaMockProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

/**
 * CA Mock 业务引擎：凭据校验、JSON 解析、故障注入（全局 / 签名人维度）与操作分派。
 * 业务成功与业务失败统一 HTTP 200（{@code code=0/-1}）；凭据错误 401，请求体非 JSON 400，
 * HTTP500 / BADJSON 故障走 HTTP 层异常。
 */
@Component
public class CaMockEngine {
    public static final Set<String> OPERATIONS = Set.of(
            "signers/enroll", "signers/query",
            "login/mobile/start", "login/mobile/poll",
            "login/certificate/challenge", "login/certificate/verify",
            "signing/submit", "signing/query", "signing/query-batch", "signing/fetch", "signing/cancel",
            "grants/request", "grants/query", "grants/revoke",
            "timestamp/stamp", "timestamp/verify",
            "trust/anchors", "trust/envelope-binds", "trust/verify-signature", "trust/certificate-status");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String JSON = "application/json;charset=UTF-8";

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final CaMockProperties properties;
    private final CaMockConfigService configs;
    private final CaVendorService service;
    private final ExchangeLog log;

    public CaMockEngine(CaMockProperties properties, CaMockConfigService configs, CaVendorService service,
                        ExchangeLog log) {
        this.properties = properties;
        this.configs = configs;
        this.service = service;
        this.log = log;
    }

    public MockHttpResponse handle(String op, String rawBody, String clientId, String clientSecret) {
        CaMockConfig config = configs.current();
        if (!config.clientSecret().isBlank()
                && !(config.clientSecret().equals(clientSecret) && properties.clientId().equals(clientId))) {
            return MockHttpResponse.raw(401, "{\"error\":\"X-Client-Id / X-Client-Secret 缺失或不匹配\"}", JSON);
        }
        if (!OPERATIONS.contains(op)) {
            return MockHttpResponse.raw(404, "{\"error\":\"不支持的 CA 操作: " + op + "\"}", JSON);
        }
        JsonNode body;
        try {
            body = rawBody == null || rawBody.isBlank() ? NODES.objectNode() : mapper.readTree(rawBody);
        } catch (RuntimeException failure) {
            return MockHttpResponse.raw(400, "{\"error\":\"请求体不是合法 JSON\"}", JSON);
        }
        if (body == null || !body.isObject()) {
            return MockHttpResponse.raw(400, "{\"error\":\"请求体必须是 JSON 对象\"}", JSON);
        }
        String now = LocalDateTime.now().format(TIME);
        MockHttpResponse respond;
        try {
            respond = execute(op, body, config);
        } catch (CaFailure failure) {
            respond = MockHttpResponse.json(failure(failure.errorCode(), failure.getMessage()));
        } catch (IllegalArgumentException failure) {
            respond = MockHttpResponse.json(failure("CA_INVALID_ARGUMENT", failure.getMessage()));
        }
        log.add(new ExchangeLog.Entry(now, CaProtocol.ID, op, reference(body), respond.httpStatus(), rawBody,
                respond.body()));
        return respond;
    }

    private MockHttpResponse execute(String op, JsonNode body, CaMockConfig config) {
        sleep(config.forceDelayMs());
        String fault = "NONE";
        if (!"NONE".equals(config.fault()) && (config.faultOp().isBlank() || config.faultOp().equals(op))) {
            fault = config.fault();
        } else {
            Behavior behavior = service.behaviorOf(op, body);
            if (behavior == Behavior.SLOW || behavior == Behavior.TIMEOUT || behavior == Behavior.HTTP500
                    || behavior == Behavior.BADJSON) {
                fault = behavior.name();
            }
        }
        switch (fault) {
            case "HTTP500" -> {
                return MockHttpResponse.raw(500, "{\"error\":\"simulated " + op + " http 500\"}", JSON);
            }
            case "BADJSON" -> {
                return MockHttpResponse.raw(200, "<html>not a json response</html>", "text/plain;charset=UTF-8");
            }
            case "SLOW" -> sleep(config.slowDelayMs());
            case "TIMEOUT" -> sleep(config.timeoutDelayMs());
            default -> {
            }
        }
        if (config.forceErrorCode() != null) {
            return MockHttpResponse.json(failure(config.forceErrorCode(),
                    config.forceMessage() == null ? "强制应答" : config.forceMessage()));
        }
        return MockHttpResponse.json(success(dispatch(op, body)));
    }

    private ObjectNode dispatch(String op, JsonNode body) {
        return switch (op) {
            case "signers/enroll" -> service.enroll(body);
            case "signers/query" -> service.querySigner(body);
            case "login/mobile/start" -> service.loginStart(body);
            case "login/mobile/poll" -> service.loginPoll(body);
            case "login/certificate/challenge" -> service.certificateChallenge();
            case "login/certificate/verify" -> service.certificateVerify(body);
            case "signing/submit" -> service.submit(body);
            case "signing/query" -> service.queryOrder(body);
            case "signing/query-batch" -> service.queryBatch(body);
            case "signing/fetch" -> service.fetch(body);
            case "signing/cancel" -> service.cancel(body);
            case "grants/request" -> service.grantRequest(body);
            case "grants/query" -> service.grantQuery(body);
            case "grants/revoke" -> service.grantRevoke(body);
            case "timestamp/stamp" -> service.stamp(body);
            case "timestamp/verify" -> service.verifyStamp(body);
            case "trust/anchors" -> service.trustAnchors();
            case "trust/envelope-binds" -> service.envelopeBinds(body);
            case "trust/verify-signature" -> service.verifySignature(body);
            default -> service.certificateStatus(body);
        };
    }

    private String success(ObjectNode data) {
        ObjectNode node = NODES.objectNode();
        node.put("code", 0);
        node.put("message", "OK");
        node.put("requestId", UUID.randomUUID().toString());
        node.set("data", data);
        return mapper.writeValueAsString(node);
    }

    private String failure(String errorCode, String message) {
        ObjectNode node = NODES.objectNode();
        node.put("code", -1);
        node.put("errorCode", errorCode);
        node.put("message", message);
        node.put("requestId", UUID.randomUUID().toString());
        node.putNull("data");
        return mapper.writeValueAsString(node);
    }

    private static String reference(JsonNode body) {
        for (String field : new String[]{"externalOrderId", "signingOrderId", "externalSignerId", "externalRequestId",
                "challengeId", "userId"}) {
            String value = JsonNodes.text(body, field);
            if (value != null) return value;
        }
        return null;
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
