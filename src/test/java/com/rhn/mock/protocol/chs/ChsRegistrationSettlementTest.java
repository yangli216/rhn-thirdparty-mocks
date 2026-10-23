package com.rhn.mock.protocol.chs;

import com.rhn.mock.protocol.chs.support.ChsTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/** 挂号时医保结算补充（接口要求 §8）：2202、无诊断 2204、同就诊多次结算、2208 核对、暂停参保、签到只失效一次。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChsRegistrationSettlementTest {
    private static final String OP = ChsTestClient.BUSINESS_OPERATOR;

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

    private record Reg(String psnNo, String insutype, String mdtrtId) {
    }

    private Reg register(String certNo) {
        ChsTestClient.Result person = client.person(certNo, "02", "张三");
        String psnNo = person.output().path("baseinfo").path("psn_no").asString();
        String insutype = ChsTestClient.activeInsutype(person.output().path("insuinfo"));
        ChsTestClient.Result register = client.register(psnNo, insutype, "02", certNo, OP);
        assertThat(register.succeeded()).isTrue();
        return new Reg(psnNo, insutype, register.output().path("data").path("mdtrt_id").asString());
    }

    private String settleCycle(Reg reg) {
        if (client.get("/mock/config?protocol=chs").json().path("diagnosisRequired").asBoolean()) {
            assertThat(client.diagnosis(reg.mdtrtId(), reg.psnNo(), OP).succeeded()).isTrue();
        }
        assertThat(client.feedetails(reg.mdtrtId(), reg.psnNo(), "B1", OP, ChsTestClient.defaultLines()).succeeded()).isTrue();
        assertThat(client.preSettle(reg.mdtrtId(), reg.psnNo(), reg.insutype(), "02", "TEST-310",
                ChsTestClient.defaultTotal(), "B1", true, OP).succeeded()).isTrue();
        ChsTestClient.Result settled = client.settle(reg.mdtrtId(), reg.psnNo(), reg.insutype(), "B1", true, OP);
        assertThat(settled.succeeded()).isTrue();
        return settled.output().path("setlinfo").path("setl_id").asString();
    }

    // ==================== 2202 ====================

    @Test
    void cancelRegistrationThenRepeatIsRejected() {
        Reg reg = register("TEST-310");
        assertThat(client.cancelRegistration(reg.mdtrtId(), reg.psnNo(), OP).succeeded()).isTrue();
        ChsTestClient.Result again = client.cancelRegistration(reg.mdtrtId(), reg.psnNo(), OP);
        assertThat(again.infcode()).isEqualTo(-1);
        assertThat(again.errMsg()).contains("已撤销");
        // 撤销后的就诊不能再用
        assertThat(client.diagnosis(reg.mdtrtId(), reg.psnNo(), OP).infcode()).isEqualTo(-1);
    }

    @Test
    void cancelUnknownRegistrationIsRejected() {
        client.signIn(OP);
        ChsTestClient.Result result = client.cancelRegistration("MDT-UNKNOWN", "PSN-TEST-310", OP);
        assertThat(result.infcode()).isEqualTo(-1);
        assertThat(result.errMsg()).contains("未登记");
    }

    @Test
    void cancelRegistrationWithWrongPersonIsRejected() {
        Reg reg = register("TEST-310");
        ChsTestClient.Result result = client.cancelRegistration(reg.mdtrtId(), "PSN-OTHER", OP);
        assertThat(result.infcode()).isEqualTo(-1);
        assertThat(result.errMsg()).contains("不一致");
    }

    @Test
    void cancelSettledRegistrationRequiresReversalFirst() {
        Reg reg = register("TEST-310");
        String setlId = settleCycle(reg);
        ChsTestClient.Result rejected = client.cancelRegistration(reg.mdtrtId(), reg.psnNo(), OP);
        assertThat(rejected.infcode()).isEqualTo(-1);
        assertThat(rejected.errMsg()).contains("2208");

        assertThat(client.reverse(setlId, reg.mdtrtId(), reg.psnNo(), OP).succeeded()).isTrue();
        assertThat(client.cancelRegistration(reg.mdtrtId(), reg.psnNo(), OP).succeeded()).isTrue();
    }

    // ==================== 2205 ====================

    @Test
    void cancelFeedetailsVoidsPreSettlementAndAllowsReuploadThenRepeatIsRejected() {
        Reg reg = register("TEST-310");
        assertThat(client.feedetails(reg.mdtrtId(), reg.psnNo(), "B1", OP, ChsTestClient.defaultLines()).succeeded()).isTrue();
        assertThat(client.preSettle(reg.mdtrtId(), reg.psnNo(), reg.insutype(), "02", "TEST-310",
                ChsTestClient.defaultTotal(), "B1", true, OP).succeeded()).isTrue();

        assertThat(client.cancelFeedetails(reg.mdtrtId(), reg.psnNo(), "B1", OP).succeeded()).isTrue();
        // 预结算随费用明细作废：不能直接结算，也不能再预结算
        assertThat(client.settle(reg.mdtrtId(), reg.psnNo(), reg.insutype(), "B1", true, OP).errMsg()).contains("2206");
        assertThat(client.preSettle(reg.mdtrtId(), reg.psnNo(), reg.insutype(), "02", "TEST-310",
                ChsTestClient.defaultTotal(), "B1", true, OP).errMsg()).contains("2204");
        ChsTestClient.Result again = client.cancelFeedetails(reg.mdtrtId(), reg.psnNo(), "B1", OP);
        assertThat(again.infcode()).isEqualTo(-1);
        assertThat(again.errMsg()).contains("没有可撤销");
        // 就诊登记仍有效，可重新上传
        assertThat(client.feedetails(reg.mdtrtId(), reg.psnNo(), "B2", OP, ChsTestClient.defaultLines()).succeeded()).isTrue();
    }

    @Test
    void cancelFeedetailsRejectsUnknownVisitWrongPersonAndSettledVisit() {
        client.signIn(OP);
        ChsTestClient.Result unknown = client.cancelFeedetails("MDT-UNKNOWN", "PSN-TEST-310", "B1", OP);
        assertThat(unknown.infcode()).isEqualTo(-1);
        assertThat(unknown.errMsg()).contains("未登记");

        Reg reg = register("TEST-310");
        assertThat(client.cancelFeedetails(reg.mdtrtId(), "PSN-OTHER", "B1", OP).errMsg()).contains("不一致");

        String setlId = settleCycle(reg);
        ChsTestClient.Result settled = client.cancelFeedetails(reg.mdtrtId(), reg.psnNo(), "B1", OP);
        assertThat(settled.infcode()).isEqualTo(-1);
        assertThat(settled.errMsg()).contains("2208");
        assertThat(client.reverse(setlId, reg.mdtrtId(), reg.psnNo(), OP).succeeded()).isTrue();
    }

    // ==================== diagnosisRequired ====================

    @Test
    void feeUploadWithoutDiagnosisPassesByDefault() {
        Reg reg = register("TEST-310");
        // 挂号时结算：2201 → 2204 → 2206 → 2207，不上传 2203A
        settleCycle(reg);
        assertThat(client.get("/mock/config?protocol=chs").json().path("diagnosisRequired").asBoolean()).isFalse();
    }

    @Test
    void diagnosisNotRequiredByDefaultCanBeEnabledAndRestoredByReset() {
        assertThat(client.get("/mock/config?protocol=chs").json().path("diagnosisRequired").asBoolean()).isFalse();
        assertThat(client.putConfig("{\"diagnosisRequired\":true}").status()).isEqualTo(200);
        Reg reg = register("TEST-310");
        ChsTestClient.Result rejected = client.feedetails(reg.mdtrtId(), reg.psnNo(), "B1", OP, ChsTestClient.defaultLines());
        assertThat(rejected.infcode()).isEqualTo(-1);
        assertThat(rejected.errMsg()).contains("2203A");
        client.reset();
        assertThat(client.get("/mock/config?protocol=chs").json().path("diagnosisRequired").asBoolean()).isFalse();
    }

    // ==================== 同一就诊再次结算 ====================

    @Test
    void sameVisitCanBeSettledAgainAfterDiagnosisButNotTwiceForSameSettlement() {
        client.putConfig("{\"diagnosisRequired\":false}");
        Reg reg = register("TEST-310");
        settleCycle(reg);

        assertThat(client.diagnosis(reg.mdtrtId(), reg.psnNo(), OP).succeeded()).isTrue();
        String second = settleCycle(reg);
        assertThat(second).isNotBlank();

        ChsTestClient.Result repeat = client.settle(reg.mdtrtId(), reg.psnNo(), reg.insutype(), "B1", true, OP);
        assertThat(repeat.infcode()).isEqualTo(-1);
        assertThat(repeat.errMsg()).contains("重复结算");
    }

    // ==================== 2208 核对 ====================

    @Test
    void reversalMustMatchMdtrtAndPerson() {
        Reg reg = register("TEST-310");
        String setlId = settleCycle(reg);
        ChsTestClient.Result wrongMdtrt = client.reverse(setlId, "MDT-OTHER", reg.psnNo(), OP);
        assertThat(wrongMdtrt.infcode()).isEqualTo(-1);
        assertThat(wrongMdtrt.errMsg()).contains("就诊");
        ChsTestClient.Result wrongPsn = client.reverse(setlId, reg.mdtrtId(), "PSN-OTHER", OP);
        assertThat(wrongPsn.infcode()).isEqualTo(-1);
        assertThat(wrongPsn.errMsg()).contains("人员");
        assertThat(client.reverse(setlId, reg.mdtrtId(), reg.psnNo(), OP).succeeded()).isTrue();
    }

    // ==================== 暂停参保 ====================

    @Test
    void suspendedPersonIsRejectedAt2201() {
        ChsTestClient.Result person = client.person("TEST-SUSPENDED", "02", "张三");
        String psnNo = person.output().path("baseinfo").path("psn_no").asString();
        ChsTestClient.Result register = client.register(psnNo, "310", "02", "TEST-SUSPENDED", OP);
        assertThat(register.infcode()).isEqualTo(-1);
        assertThat(register.errMsg()).contains("暂停");
    }

    // ==================== TEST-SIGN-EXPIRE 只失效一次 ====================

    @Test
    void signExpireInvalidatesOnlyOnce() {
        ChsTestClient.Result person = client.person("TEST-SIGN-EXPIRE", "02", "张三");
        String psnNo = person.output().path("baseinfo").path("psn_no").asString();
        ChsTestClient.Result register = client.register(psnNo, "310", "02", "TEST-SIGN-EXPIRE", OP);
        assertThat(register.succeeded()).isTrue();
        String mdtrtId = register.output().path("data").path("mdtrt_id").asString();
        ChsTestClient.Result expired = client.diagnosis(mdtrtId, psnNo, OP);
        assertThat(expired.infcode()).isEqualTo(-1);
        assertThat(expired.errMsg()).contains("签到");

        assertThat(client.signIn(OP).succeeded()).isTrue();
        assertThat(client.diagnosis(mdtrtId, psnNo, OP).succeeded()).isTrue();
        assertThat(client.diagnosis(mdtrtId, psnNo, OP).succeeded()).isTrue();
    }
}
