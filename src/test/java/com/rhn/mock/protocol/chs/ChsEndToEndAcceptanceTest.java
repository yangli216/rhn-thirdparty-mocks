package com.rhn.mock.protocol.chs;

import com.rhn.mock.protocol.chs.support.ChsTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 需求 §7 验收：TEST-310 走通 9001 → 1101 → 2201 → 2203A → 2204 → 2206 → 2207 → 2208，报文齐全且守恒。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChsEndToEndAcceptanceTest {
    private static final List<String> ENVELOPE_FIELDS = List.of(
            "infno", "msgid", "mdtrtarea_admvs", "insuplc_admdvs", "recer_sys_code", "dev_no", "dev_safe_info",
            "cainfo", "signtype", "infver", "opter_type", "opter", "opter_name", "inf_time", "fixmedins_code",
            "fixmedins_name", "sign_no", "input");

    @LocalServerPort
    int port;

    ChsTestClient client;

    @BeforeEach
    void setUp() {
        client = new ChsTestClient(port);
        client.reset();
        client.resetConfig();
    }

    @Test
    void fullChainSucceedsAndLogsEveryExchange() {
        ChsTestClient.PreFlow flow = client.toPreSettle("TEST-310", true);
        assertThat(flow.person().succeeded()).isTrue();
        assertThat(flow.register().succeeded()).isTrue();
        assertThat(flow.diagnosis().succeeded()).isTrue();
        assertThat(flow.feedetails().succeeded()).isTrue();
        assertThat(flow.preSettle().succeeded()).isTrue();

        assertThat(flow.person().output().path("baseinfo").path("psn_no").asString()).isEqualTo("PSN-TEST-310");
        assertThat(flow.person().output().path("baseinfo").path("psn_name").asString()).isEqualTo("张三");
        assertThat(flow.person().output().path("insuinfo").get(0).path("balc").asString()).isEqualTo("1850.00");
        assertThat(flow.register().output().path("data").path("mdtrt_id").asString()).startsWith("MDT");
        assertThat(flow.feedetails().output().path("result").size()).isEqualTo(3);
        assertThat(flow.feedetails().output().path("result").get(0).path("chrgitm_lv").asString()).isEqualTo("01");
        assertThat(flow.feedetails().output().path("result").get(1).path("chrgitm_lv").asString()).isEqualTo("02");
        assertThat(flow.feedetails().output().path("result").get(2).path("chrgitm_lv").asString()).isEqualTo("03");

        JsonNode preSetlinfo = flow.preSettle().output().path("setlinfo");
        assertThat(preSetlinfo.path("setl_id").asString()).startsWith("PRE");
        assertThat(preSetlinfo.path("medfee_sumamt").asString()).isEqualTo("180.00");
        assertThat(preSetlinfo.path("fund_pay_sumamt").asString()).isEqualTo("109.75");
        assertThat(preSetlinfo.path("hifp_pay").asString()).isEqualTo("109.75");
        assertThat(preSetlinfo.path("hifob_pay").asString()).isEqualTo("0.00");
        assertThat(preSetlinfo.path("acct_pay").asString()).isEqualTo("70.25");
        assertThat(preSetlinfo.path("psn_cash_pay").asString()).isEqualTo("0.00");
        assertThat(preSetlinfo.path("fulamt_ownpay_amt").asString()).isEqualTo("30.00");
        assertThat(preSetlinfo.path("preselfpay_amt").asString()).isEqualTo("7.50");
        assertThat(preSetlinfo.path("inscp_scp_amt").asString()).isEqualTo("150.00");
        assertConserved(preSetlinfo);

        ChsTestClient.Result settled = client.settle(flow.mdtrtId(), flow.psnNo(), flow.insutype(), flow.chrgBchno(),
                true, ChsTestClient.BUSINESS_OPERATOR);
        assertThat(settled.succeeded()).isTrue();
        JsonNode setlinfo = settled.output().path("setlinfo");
        assertThat(setlinfo.path("setl_id").asString()).startsWith("SETL");
        assertThat(setlinfo.path("setl_time").asString()).isNotBlank();
        assertThat(setlinfo.path("medfee_sumamt").asString()).isEqualTo("180.00");
        assertConserved(setlinfo);

        String setlId = setlinfo.path("setl_id").asString();
        ChsTestClient.Result reversed = client.reverse(setlId, flow.mdtrtId(), flow.psnNo(), ChsTestClient.BUSINESS_OPERATOR);
        assertThat(reversed.succeeded()).isTrue();
        assertThat(reversed.output().path("setlinfo").path("setl_id").asString()).startsWith("REV");

        ChsTestClient.Result requests = client.get("/mock/requests");
        assertThat(requests.status()).isEqualTo(200);
        JsonNode array = requests.json();
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(9);
        List<String> infnos = new ArrayList<>();
        for (JsonNode entry : array) {
            assertThat(entry.path("time").asString()).isNotBlank();
            assertThat(entry.path("infno").asString()).isNotBlank();
            assertThat(entry.path("msgid").asString()).isNotBlank();
            assertThat(entry.path("httpStatus").asString()).isEqualTo("200");
            assertThat(entry.path("request").asString()).isNotBlank();
            assertThat(entry.path("response").asString()).isNotBlank();
            JsonNode sent = client.read(entry.path("request").asString());
            for (String field : ENVELOPE_FIELDS) {
                assertThat(sent.has(field)).as("已收请求缺字段 " + field).isTrue();
            }
            assertThat(sent.path("input").isObject()).isTrue();
            infnos.add(entry.path("infno").asString());
        }
        assertThat(infnos).containsExactlyInAnyOrder("9001", "9001", "1101", "2201", "2203A", "2204", "2206", "2207", "2208");
        assertThat(entryOf(array, "2206").path("response").asString()).contains("\"infcode\":0");
    }

    private static JsonNode entryOf(JsonNode array, String infno) {
        for (JsonNode entry : array) if (infno.equals(entry.path("infno").asString())) return entry;
        throw new AssertionError("未找到 " + infno + " 的报文");
    }

    private static void assertConserved(JsonNode setlinfo) {
        BigDecimal total = new BigDecimal(setlinfo.path("medfee_sumamt").asString());
        BigDecimal right = new BigDecimal(setlinfo.path("fund_pay_sumamt").asString())
                .add(new BigDecimal(setlinfo.path("acct_pay").asString()))
                .add(new BigDecimal(setlinfo.path("psn_cash_pay").asString()));
        assertThat(right).isEqualByComparingTo(total);
        BigDecimal fund = new BigDecimal(setlinfo.path("fund_pay_sumamt").asString());
        BigDecimal pool = new BigDecimal(setlinfo.path("hifp_pay").asString())
                .add(new BigDecimal(setlinfo.path("hifob_pay").asString()));
        assertThat(fund).isGreaterThanOrEqualTo(pool);
    }
}
