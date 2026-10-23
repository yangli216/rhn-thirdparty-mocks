package com.rhn.mock.protocol.chs;

import com.rhn.mock.protocol.chs.support.ChsTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 需求 §5 管理端点：reset / requests / config / health。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChsAdminEndpointTest {
    @LocalServerPort
    int port;

    ChsTestClient client;

    @BeforeEach
    void setUp() {
        client = new ChsTestClient(port);
        client.reset();
        client.resetConfig();
    }

    @AfterEach
    void tearDown() {
        client.resetConfig();
    }

    @Test
    void healthIsUpAndCountsTransactions() {
        ChsTestClient.Result health = client.get("/mock/health");
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.json().path("status").asString()).isEqualTo("UP");
        assertThat(health.json().path("transactions").asString()).isEqualTo("0");

        client.signIn("MOCKOP");
        ChsTestClient.Result after = client.get("/mock/health");
        assertThat(after.json().path("transactions").asString()).isEqualTo("1");
    }

    @Test
    void configSupportsReadAndPartialUpdate() {
        JsonNode defaults = client.get("/mock/config").json();
        assertThat(decimal(defaults, "ipPoolRatio")).isEqualByComparingTo("0.80");
        assertThat(decimal(defaults, "preselfRatio")).isEqualByComparingTo("0.15");
        assertThat(decimal(defaults, "ipPoolRatioB")).isEqualByComparingTo("0.70");
        assertThat(defaults.path("xToken").asString()).isEqualTo("mock-token");
        assertThat(defaults.path("signInRequired").asString()).isEqualTo("true");
        assertThat(defaults.path("slowDelayMs").asString()).isEqualTo("31000");
        assertThat(defaults.path("forceDelayMs").asString()).isEqualTo("0");
        assertThat(defaults.path("itemLevelPrefixes").path("X").asString()).isEqualTo("01");

        JsonNode updated = client.putConfig("{\"ipPoolRatio\":0.50,\"forceDelayMs\":25}").json();
        assertThat(decimal(updated, "ipPoolRatio")).isEqualByComparingTo("0.50");
        assertThat(updated.path("forceDelayMs").asString()).isEqualTo("25");
        assertThat(decimal(updated, "preselfRatio")).isEqualByComparingTo("0.15");

        JsonNode reread = client.get("/mock/config").json();
        assertThat(decimal(reread, "ipPoolRatio")).isEqualByComparingTo("0.50");
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(node.path(field).asString());
    }

    @Test
    void forcedResponseOverridesBusinessReply() {
        client.putConfig("{\"forceInfcode\":-1,\"forceErrMsg\":\"强制失败\"}");
        ChsTestClient.Result forced = client.signIn("MOCKOP");
        assertThat(forced.infcode()).isEqualTo(-1);
        assertThat(forced.errMsg()).isEqualTo("强制失败");

        client.putConfig("{\"forceInfcode\":null,\"forceErrMsg\":null}");
        assertThat(client.signIn("MOCKOP").succeeded()).isTrue();
    }

    @Test
    void requestsSupportFilterAndLimit() {
        client.toPreSettle("TEST-310", true);

        JsonNode filtered = client.get("/mock/requests?infno=2206").json();
        assertThat(filtered.size()).isEqualTo(1);
        assertThat(filtered.get(0).path("infno").asString()).isEqualTo("2206");
        assertThat(filtered.get(0).path("request").asString()).contains("\"infno\":\"2206\"");

        JsonNode limited = client.get("/mock/requests?limit=2").json();
        assertThat(limited.size()).isEqualTo(2);
        assertThat(limited.get(0).path("infno").asString()).isEqualTo("2206");
    }

    @Test
    void resetClearsStateAndRequests() {
        client.toPreSettle("TEST-310", true);
        assertThat(client.get("/mock/requests").json().size()).isGreaterThan(0);

        client.reset();
        assertThat(client.get("/mock/requests").json().size()).isEqualTo(0);
        assertThat(client.get("/mock/health").json().path("transactions").asString()).isEqualTo("0");

        ChsTestClient.Result register = client.register("PSN-TEST-310", "310", "02", "TEST-310",
                ChsTestClient.BUSINESS_OPERATOR);
        assertThat(register.infcode()).isEqualTo(-1);
        assertThat(register.errMsg()).contains("1101");
    }
}
