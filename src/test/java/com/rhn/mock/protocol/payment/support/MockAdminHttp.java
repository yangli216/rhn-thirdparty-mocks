package com.rhn.mock.protocol.payment.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** 测试用：调用 /mock/** 管理端点（JSON）。 */
public class MockAdminHttp {
    public record Reply(int status, JsonNode json, String body) {
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final String base;

    public MockAdminHttp(int port) {
        this.base = "http://127.0.0.1:" + port;
    }

    public Reply get(String path) {
        return send("GET", path, null);
    }

    public Reply post(String path, String body) {
        return send("POST", path, body);
    }

    public Reply put(String path, String body) {
        return send("PUT", path, body);
    }

    public Reply delete(String path) {
        return send("DELETE", path, null);
    }

    private Reply send(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json;charset=UTF-8");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = null;
            try {
                json = mapper.readTree(response.body());
            } catch (RuntimeException ignored) {
                // 非 JSON
            }
            return new Reply(response.statusCode(), json, response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("调用管理端点失败: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用管理端点被中断", exception);
        }
    }
}
