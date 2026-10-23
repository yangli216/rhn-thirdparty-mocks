package com.rhn.mock.protocol.chs;

import com.rhn.mock.protocol.chs.support.ChsTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/** 1101 身份证号 -> TEST-* 人员别名映射（mock.chs.cert-aliases）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChsCertAliasTest {
    private static final String OP = ChsTestClient.BUSINESS_OPERATOR;
    private static final String ID_310 = "330102197503129014";
    private static final String ID_NOINSU = "330102199210039129";
    private static final String ID_SUSPENDED = "330102197101309047";
    private static final String ID_REJECT = "33010219690909905X";

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
    void aliasReturnsPersonaPsnNoAndEchoesOriginalCertNo() {
        ChsTestClient.Result person = client.person(ID_310, "02", "张三");
        assertThat(person.succeeded()).isTrue();
        assertThat(person.output().path("baseinfo").path("psn_no").asString()).isEqualTo("PSN-TEST-310");
        assertThat(person.output().path("baseinfo").path("certno").asString()).isEqualTo(ID_310);
        assertThat(ChsTestClient.activeInsutype(person.output().path("insuinfo"))).isEqualTo("310");
    }

    @Test
    void aliasMatchIgnoresCaseOfTrailingXAndSurroundingSpaces() {
        ChsTestClient.Result person = client.person(" 33010219690909905x ", "02", "张三");
        assertThat(person.succeeded()).isTrue();
        assertThat(person.output().path("baseinfo").path("psn_no").asString()).isEqualTo("PSN-TEST-REJECT-2206");
    }

    @Test
    void aliasNoInsuIsRejected() {
        ChsTestClient.Result person = client.person(ID_NOINSU, "02", "张三");
        assertThat(person.infcode()).isEqualTo(-1);
        assertThat(person.errMsg()).contains("无参保");
    }

    @Test
    void aliasSuspendedIsRejectedAt2201() {
        ChsTestClient.Result person = client.person(ID_SUSPENDED, "02", "张三");
        assertThat(person.succeeded()).isTrue();
        assertThat(person.output().path("baseinfo").path("psn_no").asString()).isEqualTo("PSN-TEST-SUSPENDED");
        String insutype = ChsTestClient.activeInsutype(person.output().path("insuinfo"));
        ChsTestClient.Result register = client.register("PSN-TEST-SUSPENDED", insutype, "02", ID_SUSPENDED, OP);
        assertThat(register.infcode()).isEqualTo(-1);
        assertThat(register.errMsg()).contains("暂停");
    }

    @Test
    void aliasReject2206IsRejectedAtPreSettle() {
        ChsTestClient.PreFlow flow = client.toPreSettle(ID_REJECT, true);
        assertThat(flow.psnNo()).isEqualTo("PSN-TEST-REJECT-2206");
        assertThat(flow.reachedPreSettle()).isTrue();
        assertThat(flow.preSettle().infcode()).isEqualTo(-1);
    }

    @Test
    void unmappedCertKeepsPsnPrefixedCertNo() {
        ChsTestClient.Result person = client.person("110101199001011234", "02", "张三");
        assertThat(person.succeeded()).isTrue();
        assertThat(person.output().path("baseinfo").path("psn_no").asString()).isEqualTo("PSN-110101199001011234");
        assertThat(person.output().path("baseinfo").path("certno").asString()).isEqualTo("110101199001011234");
    }

    @Test
    void aliasCertWalksThrough2201To2206() {
        ChsTestClient.PreFlow flow = client.toPreSettle(ID_310, true);
        assertThat(flow.psnNo()).isEqualTo("PSN-TEST-310");
        assertThat(flow.register().succeeded()).isTrue();
        assertThat(flow.feedetails().succeeded()).isTrue();
        assertThat(flow.reachedPreSettle()).isTrue();
        assertThat(flow.preSettle().succeeded()).isTrue();
    }
}
