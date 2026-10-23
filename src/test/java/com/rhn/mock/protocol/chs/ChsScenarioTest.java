package com.rhn.mock.protocol.chs;

import com.rhn.mock.protocol.chs.support.ChsTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 需求 §4 场景与顺序／字段／守恒拒绝。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChsScenarioTest {
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

    // ==================== §4 人员与故障场景 ====================

    @Test
    void residentHasNoPersonalAccount() {
        ChsTestClient.PreFlow flow = client.toPreSettle("TEST-390", true);
        assertThat(flow.insutype()).isEqualTo("390");
        assertThat(flow.person().output().path("insuinfo").get(0).path("balc").isMissingNode()).isTrue();
        assertThat(flow.preSettle().succeeded()).isTrue();
        JsonNode setlinfo = flow.preSettle().output().path("setlinfo");
        assertThat(setlinfo.path("acct_pay").asString()).isEqualTo("0.00");
        assertThat(setlinfo.path("psn_cash_pay").asString()).isEqualTo("70.25");
        assertConserved(setlinfo);
    }

    @Test
    void multiInsuranceExposesBothRowsAndUsesActiveOne() {
        ChsTestClient.Result person = client.person("TEST-MULTI", "02", "张三");
        assertThat(person.succeeded()).isTrue();
        JsonNode insuinfo = person.output().path("insuinfo");
        assertThat(insuinfo.size()).isEqualTo(2);
        assertThat(insuinfo.get(0).path("psn_insu_stas").asString()).isEqualTo("3");
        assertThat(insuinfo.get(1).path("psn_insu_stas").asString()).isEqualTo("1");
        assertThat(ChsTestClient.activeInsutype(insuinfo)).isEqualTo("310");

        ChsTestClient.PreFlow flow = client.toPreSettle("TEST-MULTI", true);
        assertThat(flow.insutype()).isEqualTo("310");
        assertThat(flow.preSettle().succeeded()).isTrue();
    }

    @Test
    void suspendedPersonReportsSuspendedStatus() {
        ChsTestClient.Result person = client.person("TEST-SUSPENDED", "02", "张三");
        assertThat(person.succeeded()).isTrue();
        assertThat(person.output().path("insuinfo").get(0).path("psn_insu_stas").asString()).isEqualTo("2");
    }

    @Test
    void noInsuranceIsRejected() {
        ChsTestClient.Result person = client.person("TEST-NOINSU", "02", "张三");
        assertThat(person.infcode()).isEqualTo(-1);
        assertThat(person.errMsg()).contains("无参保信息");
    }

    @Test
    void preSettleRejectionScenarioRejects2206AfterSuccessfulPrelude() {
        ChsTestClient.PreFlow flow = client.toPreSettle("TEST-REJECT-2206", true);
        assertThat(flow.register().succeeded()).isTrue();
        assertThat(flow.diagnosis().succeeded()).isTrue();
        assertThat(flow.feedetails().succeeded()).isTrue();
        assertThat(flow.preSettle().infcode()).isEqualTo(-1);
        assertThat(flow.preSettle().errMsg()).contains("未结算");
    }

    @Test
    void imbalanceScenarioViolatesConservation() {
        ChsTestClient.PreFlow flow = client.toPreSettle("TEST-IMBALANCE", true);
        assertThat(flow.preSettle().succeeded()).isTrue();
        JsonNode setlinfo = flow.preSettle().output().path("setlinfo");
        BigDecimal total = new BigDecimal(setlinfo.path("medfee_sumamt").asString());
        BigDecimal right = new BigDecimal(setlinfo.path("fund_pay_sumamt").asString())
                .add(new BigDecimal(setlinfo.path("acct_pay").asString()))
                .add(new BigDecimal(setlinfo.path("psn_cash_pay").asString()));
        assertThat(right).isNotEqualByComparingTo(total);
    }

    @Test
    void missingAmountScenarioOmitsAccountPay() {
        ChsTestClient.PreFlow flow = client.toPreSettle("TEST-MISSING-AMT", true);
        assertThat(flow.preSettle().succeeded()).isTrue();
        JsonNode setlinfo = flow.preSettle().output().path("setlinfo");
        assertThat(setlinfo.has("acct_pay")).isFalse();
        assertThat(setlinfo.has("medfee_sumamt")).isTrue();
        assertThat(setlinfo.has("psn_cash_pay")).isTrue();
    }

    @Test
    void slowScenarioDelaysPreSettle() {
        client.putConfig("{\"slowDelayMs\":1200}");
        long begin = System.nanoTime();
        ChsTestClient.PreFlow flow = client.toPreSettle("TEST-SLOW", true);
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
        assertThat(flow.preSettle().succeeded()).isTrue();
        assertThat(elapsedMs).isGreaterThanOrEqualTo(1200);
    }

    @Test
    void settleHttp500ScenarioReturnsHttp500() {
        ChsTestClient.PreFlow flow = client.toPreSettle("TEST-HTTP500", true);
        assertThat(flow.preSettle().succeeded()).isTrue();
        ChsTestClient.Result settled = client.settle(flow.mdtrtId(), flow.psnNo(), flow.insutype(), flow.chrgBchno(),
                true, ChsTestClient.BUSINESS_OPERATOR);
        assertThat(settled.status()).isEqualTo(500);
    }

    @Test
    void settleBadJsonScenarioReturnsNonJson() {
        ChsTestClient.PreFlow flow = client.toPreSettle("TEST-BADJSON", true);
        assertThat(flow.preSettle().succeeded()).isTrue();
        ChsTestClient.Result settled = client.settle(flow.mdtrtId(), flow.psnNo(), flow.insutype(), flow.chrgBchno(),
                true, ChsTestClient.BUSINESS_OPERATOR);
        assertThat(settled.status()).isEqualTo(200);
        assertThat(settled.json()).isNull();
        assertThat(settled.body()).contains("not a json");
    }

    @Test
    void signExpireScenarioInvalidatesSignAfterFirstBusinessTransaction() {
        ChsTestClient.PreFlow flow = client.toPreSettle("TEST-SIGN-EXPIRE", true);
        assertThat(flow.register().succeeded()).isTrue();
        assertThat(flow.diagnosis().infcode()).isEqualTo(-1);
        assertThat(flow.diagnosis().errMsg()).contains("签到");
        assertThat(flow.reachedPreSettle()).isFalse();
    }

    // ==================== 顺序错误 ====================

    @Test
    void feeUploadWithoutDiagnosisIsRejectedWhenRequired() {
        client.putConfig("{\"diagnosisRequired\":true}");
        Reg reg = register("TEST-310");
        ChsTestClient.Result upload = client.feedetails(reg.mdtrtId(), reg.psnNo(), "B001",
                ChsTestClient.BUSINESS_OPERATOR, ChsTestClient.defaultLines());
        assertThat(upload.infcode()).isEqualTo(-1);
        assertThat(upload.errMsg()).contains("2203A");
    }

    @Test
    void settleWithoutPreSettleIsRejected() {
        Reg reg = register("TEST-310");
        client.diagnosis(reg.mdtrtId(), reg.psnNo(), ChsTestClient.BUSINESS_OPERATOR);
        client.feedetails(reg.mdtrtId(), reg.psnNo(), "B001", ChsTestClient.BUSINESS_OPERATOR, ChsTestClient.defaultLines());
        ChsTestClient.Result settled = client.settle(reg.mdtrtId(), reg.psnNo(), reg.insutype(), "B001",
                true, ChsTestClient.BUSINESS_OPERATOR);
        assertThat(settled.infcode()).isEqualTo(-1);
        assertThat(settled.errMsg()).contains("预结算");
    }

    @Test
    void registerWithoutPersonInfoIsRejected() {
        ChsTestClient.Result register = client.register("PSN-UNKNOWN", "310", "02", "TEST-310",
                ChsTestClient.BUSINESS_OPERATOR);
        assertThat(register.infcode()).isEqualTo(-1);
        assertThat(register.errMsg()).contains("1101");
    }

    @Test
    void duplicateSettlementIsRejected() {
        ChsTestClient.PreFlow flow = client.toPreSettle("TEST-310", true);
        ChsTestClient.Result first = client.settle(flow.mdtrtId(), flow.psnNo(), flow.insutype(), flow.chrgBchno(),
                true, ChsTestClient.BUSINESS_OPERATOR);
        assertThat(first.succeeded()).isTrue();
        ChsTestClient.Result second = client.settle(flow.mdtrtId(), flow.psnNo(), flow.insutype(), flow.chrgBchno(),
                true, ChsTestClient.BUSINESS_OPERATOR);
        assertThat(second.infcode()).isEqualTo(-1);
        assertThat(second.errMsg()).contains("重复结算");
    }

    @Test
    void duplicateReversalIsRejected() {
        ChsTestClient.PreFlow flow = client.toPreSettle("TEST-310", true);
        ChsTestClient.Result settled = client.settle(flow.mdtrtId(), flow.psnNo(), flow.insutype(), flow.chrgBchno(),
                true, ChsTestClient.BUSINESS_OPERATOR);
        String setlId = settled.output().path("setlinfo").path("setl_id").asString();
        assertThat(client.reverse(setlId, flow.mdtrtId(), flow.psnNo(), ChsTestClient.BUSINESS_OPERATOR).succeeded()).isTrue();
        ChsTestClient.Result second = client.reverse(setlId, flow.mdtrtId(), flow.psnNo(), ChsTestClient.BUSINESS_OPERATOR);
        assertThat(second.infcode()).isEqualTo(-1);
        assertThat(second.errMsg()).contains("已撤销");
    }

    @Test
    void reversalOfUnknownSettlementIsRejected() {
        ChsTestClient.Result reversed = client.reverse("SETL-UNKNOWN", "MDT-UNKNOWN", "PSN-TEST-310",
                ChsTestClient.BUSINESS_OPERATOR);
        assertThat(reversed.infcode()).isEqualTo(-1);
        assertThat(reversed.errMsg()).contains("未结算");
    }

    // ==================== 报文与金额校验 ====================

    @Test
    void missingEnvelopeFieldIsRejected() {
        ObjectNode root = client.envelope("9001", "OP9", "", client.signInInput("OP9"));
        root.remove("cainfo");
        ChsTestClient.Result result = client.post(client.write(root));
        assertThat(result.infcode()).isEqualTo(-1);
        assertThat(result.errMsg()).contains("缺少必填字段").contains("cainfo");
    }

    @Test
    void blankRequiredFieldIsRejected() {
        ObjectNode root = client.envelope("9001", "OP9", "", client.signInInput("OP9"));
        root.put("inf_time", "");
        ChsTestClient.Result result = client.post(client.write(root));
        assertThat(result.infcode()).isEqualTo(-1);
        assertThat(result.errMsg()).contains("inf_time");
    }

    @Test
    void settledWithoutSignInIsRejected() {
        ObjectNode root = client.envelope("1101", "NOSIGN", "", client.object());
        ChsTestClient.Result result = client.post(client.write(root));
        assertThat(result.infcode()).isEqualTo(-1);
        assertThat(result.errMsg()).contains("签到");
    }

    @Test
    void unsupportedInfnoIsRejected() {
        ChsTestClient.Result result = client.business("9999", ChsTestClient.DEFAULT_OPERATOR, "", client.object());
        assertThat(result.infcode()).isEqualTo(-1);
        assertThat(result.errMsg()).contains("9999");
    }

    @Test
    void duplicateMsgIdIsRejected() {
        String body = client.envelopeBody("9001", "OP9", "", client.signInInput("OP9"));
        assertThat(client.post(body).succeeded()).isTrue();
        ChsTestClient.Result second = client.post(body);
        assertThat(second.infcode()).isEqualTo(-1);
        assertThat(second.errMsg()).contains("重复");
    }

    @Test
    void feeAmountMismatchIsRejected() {
        Reg reg = register("TEST-310");
        client.diagnosis(reg.mdtrtId(), reg.psnNo(), ChsTestClient.BUSINESS_OPERATOR);
        List<ChsTestClient.Line> broken = List.of(new ChsTestClient.Line("1", "XA01",
                new BigDecimal("2"), new BigDecimal("50.00"), new BigDecimal("99.00")));
        ChsTestClient.Result upload = client.feedetails(reg.mdtrtId(), reg.psnNo(), "B001",
                ChsTestClient.BUSINESS_OPERATOR, broken);
        assertThat(upload.infcode()).isEqualTo(-1);
        assertThat(upload.errMsg()).contains("数量×单价");
    }

    @Test
    void preSettleTotalMismatchIsRejected() {
        ChsTestClient.PreFlow flow = client.toPreSettle("TEST-310", true);
        ChsTestClient.Result pre = client.preSettle(flow.mdtrtId(), flow.psnNo(), flow.insutype(), "02", "TEST-310",
                new BigDecimal("999.00"), flow.chrgBchno(), true, ChsTestClient.BUSINESS_OPERATOR);
        assertThat(pre.infcode()).isEqualTo(-1);
        assertThat(pre.errMsg()).contains("费用明细合计");
    }

    @Test
    void xTokenMismatchReturns401() {
        String body = client.envelopeBody("9001", "OP9", "", client.signInInput("OP9"));
        ChsTestClient.Result result = client.postWithoutToken(body);
        assertThat(result.status()).isEqualTo(401);
    }

    // ==================== 小工具 ====================

    private record Reg(String certNo, String psnNo, String insutype, String mdtrtId) {
    }

    private Reg register(String certNo) {
        ChsTestClient.Result person = client.person(certNo, "02", "张三");
        assertThat(person.succeeded()).isTrue();
        String psnNo = person.output().path("baseinfo").path("psn_no").asString();
        String insutype = ChsTestClient.activeInsutype(person.output().path("insuinfo"));
        ChsTestClient.Result register = client.register(psnNo, insutype, "02", certNo, ChsTestClient.BUSINESS_OPERATOR);
        assertThat(register.succeeded()).isTrue();
        return new Reg(certNo, psnNo, insutype, register.output().path("data").path("mdtrt_id").asString());
    }

    private static void assertConserved(JsonNode setlinfo) {
        BigDecimal total = new BigDecimal(setlinfo.path("medfee_sumamt").asString());
        BigDecimal right = new BigDecimal(setlinfo.path("fund_pay_sumamt").asString())
                .add(new BigDecimal(setlinfo.path("acct_pay").asString()))
                .add(new BigDecimal(setlinfo.path("psn_cash_pay").asString()));
        assertThat(right).isEqualByComparingTo(total);
    }
}
