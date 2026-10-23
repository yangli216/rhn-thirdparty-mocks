package com.rhn.mock.core.web;

import com.rhn.mock.protocol.alipay.support.AlipayTestClient;
import com.rhn.mock.protocol.chs.support.ChsTestClient;
import com.rhn.mock.protocol.wxpay.support.WxpayTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 多协议管理端点：requests 按 protocol/op 过滤、config?protocol=all 汇总、reset 清空三方。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockAdminMultiProtocolTest {
    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = JsonMapper.builder().build();

    ChsTestClient chs;
    AlipayTestClient alipay;
    WxpayTestClient wxpay;

    @BeforeEach
    void setUp() {
        chs = new ChsTestClient(port);
        alipay = new AlipayTestClient(port);
        wxpay = new WxpayTestClient(port);
        reset();
        generateTraffic();
    }

    private void generateTraffic() {
        chs.signIn(ChsTestClient.DEFAULT_OPERATOR);
        alipay.pay("ADMIN-ALIPAY-1", "12.34", "285612345678901234");
        wxpay.micropay("ADMIN-WX-1", "1234", "134567890123456789");
    }

    @Test
    void requestsCanBeFilteredByProtocolAndOp() {
        JsonNode alipayEntries = get("/mock/requests?protocol=alipay&op=alipay.trade.pay");
        assertThat(alipayEntries.isArray()).isTrue();
        assertThat(alipayEntries.size()).isGreaterThan(0);
        alipayEntries.forEach(entry -> {
            assertThat(entry.path("protocol").asString()).isEqualTo("alipay");
            assertThat(entry.path("op").asString()).isEqualTo("alipay.trade.pay");
        });

        JsonNode wxpayEntries = get("/mock/requests?protocol=wxpay&op=/pay/micropay");
        assertThat(wxpayEntries.size()).isGreaterThan(0);
        wxpayEntries.forEach(entry -> {
            assertThat(entry.path("protocol").asString()).isEqualTo("wxpay");
            assertThat(entry.path("op").asString()).isEqualTo("/pay/micropay");
        });
    }

    @Test
    void infnoRemainsAChsAliasWithCompatibleFields() {
        JsonNode entries = get("/mock/requests?infno=9001");
        assertThat(entries.size()).isGreaterThan(0);
        entries.forEach(entry -> {
            assertThat(entry.path("protocol").asString()).isEqualTo("chs");
            assertThat(entry.path("infno").asString()).isEqualTo("9001");
            // 兼容单协议版本：CHS 的 op/refId 同时以 infno/msgid 暴露。
            assertThat(entry.path("msgid").isMissingNode()).isFalse();
        });
    }

    @Test
    void configAllReturnsEveryProtocolView() {
        JsonNode all = get("/mock/config?protocol=all");
        assertThat(all.path("chs").isObject()).isTrue();
        assertThat(all.path("alipay").path("gatewayPath").asString()).isEqualTo("/alipay/gateway.do");
        assertThat(all.path("wxpay").path("basePath").asString()).isEqualTo("/wxpay");
        assertThat(all.path("alipay").path("transactions").asString()).isEqualTo("1");
        assertThat(all.path("wxpay").path("transactions").asString()).isEqualTo("1");
    }

    @Test
    void defaultConfigViewStaysChsCompatible() {
        JsonNode config = get("/mock/config");
        assertThat(config.has("ipPoolRatio")).isTrue();
        assertThat(config.has("xToken")).isTrue();
        assertThat(config.has("gatewayPath")).isFalse();
        assertThat(config.has("basePath")).isFalse();
    }

    @Test
    void resetClearsAllProtocols() {
        JsonNode reset = reset();
        assertThat(reset.path("status").asString()).isEqualTo("OK");
        assertThat(toList(reset.path("reset"))).contains("chs", "alipay", "wxpay");

        assertThat(get("/mock/requests").size()).isZero();
        JsonNode all = get("/mock/config?protocol=all");
        assertThat(all.path("alipay").path("transactions").asString()).isEqualTo("0");
        assertThat(all.path("wxpay").path("transactions").asString()).isEqualTo("0");
        assertThat(all.path("wxpay").path("refunds").asString()).isEqualTo("0");
    }

    @Test
    void healthListsEveryProtocol() {
        JsonNode health = get("/mock/health");
        assertThat(health.path("status").asString()).isEqualTo("UP");
        assertThat(toList(health.path("protocols"))).contains("chs", "alipay", "wxpay");
    }

    private JsonNode reset() {
        return post("/mock/reset", "{}", "application/json;charset=UTF-8");
    }

    private java.util.List<String> toList(JsonNode array) {
        java.util.List<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private JsonNode get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build());
    }

    private JsonNode post(String path, String body, String contentType) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.body().isBlank() ? MissingNode.getInstance() : mapper.readTree(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("调用管理端点失败: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用管理端点被中断", exception);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}
