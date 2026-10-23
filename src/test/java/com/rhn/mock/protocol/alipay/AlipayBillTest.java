package com.rhn.mock.protocol.alipay;

import com.rhn.mock.protocol.alipay.support.AlipayTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 支付宝对账单：下载链接查询、账单文件格式（对齐 RHN AlipayStatementParser 的约束）、差异构造。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlipayBillTest {
    private static final String RESPONSE = "alipay_data_dataservice_bill_downloadurl_query_response";
    private static final Charset GBK = Charset.forName("GB18030");

    @LocalServerPort
    int port;

    AlipayTestClient client;
    final HttpClient http = HttpClient.newHttpClient();
    final String today = LocalDate.now().toString();

    @BeforeEach
    void setUp() {
        client = new AlipayTestClient(port);
        client.reset();
    }

    @AfterEach
    void tearDown() {
        client.reset();
    }

    private String payTwoAndRefundOne() {
        client.pay("BILL-1", "10.00", "285612345678901234");
        AlipayTestClient.Result second = client.pay("BILL-2", "20.00", "285612345678901234");
        String tradeNo = second.inner("alipay_trade_pay_response").path("trade_no").asString();
        assertThat(client.refund(tradeNo, "BILL-RF-1", "5.00").inner("alipay_trade_refund_response").path("code").asString())
                .isEqualTo("10000");
        return tradeNo;
    }

    /** 查链接 → 下载 zip → 按 GB18030 解出唯一的 CSV。 */
    private List<String> downloadLines(String date) throws Exception {
        AlipayTestClient.Result url = client.billDownloadUrl(date);
        assertThat(url.signValid(RESPONSE)).isTrue();
        JsonNode inner = url.inner(RESPONSE);
        assertThat(inner.path("code").asString()).isEqualTo("10000");
        String link = inner.path("bill_download_url").asString();
        assertThat(link).endsWith("/alipay/bill/download/" + date);
        HttpResponse<byte[]> file = http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + link.substring(link.indexOf("/alipay/bill")))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(file.statusCode()).isEqualTo(200);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file.body()), GBK)) {
            ZipEntry entry = zip.getNextEntry();
            assertThat(entry.getName()).endsWith(".csv");
            String text = new String(zip.readAllBytes(), GBK);
            assertThat(zip.getNextEntry()).isNull();
            return List.of(text.split("\r\n"));
        }
    }

    private static List<String> dataRows(List<String> lines) {
        List<String> rows = new ArrayList<>();
        boolean header = false;
        for (String line : lines) {
            if (line.startsWith("支付宝交易号,")) {
                header = true;
            } else if (header && !line.startsWith("#")) {
                rows.add(line);
            }
        }
        return rows;
    }

    private void putAnomalies(String json) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock/alipay/bill/anomalies"))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void billContainsPaymentsRefundsAndConsistentFooters() throws Exception {
        payTwoAndRefundOne();
        List<String> lines = downloadLines(today);
        assertThat(lines).anyMatch(line -> line.startsWith("#起始日期：[" + today + " "));
        String header = lines.stream().filter(line -> line.startsWith("支付宝交易号,")).findFirst().orElseThrow();
        assertThat(header).contains("商户订单号").contains("业务类型").contains("订单金额（元）").contains("商家实收（元）")
                .contains("完成时间").contains("退款批次号").contains("服务费（元）");
        List<String> rows = dataRows(lines);
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> assertThat(row.split(",", -1)).hasSameSizeAs(header.split(",", -1)));
        assertThat(rows.stream().filter(row -> row.contains(",交易,"))).hasSize(2);
        assertThat(rows.stream().filter(row -> row.contains(",退款,")).findFirst().orElseThrow())
                .contains("BILL-2").contains(",-5.00,-5.00,BILL-RF-1,");
        assertThat(lines).contains("#交易合计：2笔，合计金额：30.00元", "#退款合计：1笔，合计金额：5.00元");
    }

    @Test
    void localOnlyChannelOnlyAmountAndDuplicateDifferencesAreConstructible() throws Exception {
        payTwoAndRefundOne();
        client.pay("BILL-3", "3.00", "285612345678901234");
        putAnomalies("{\"omit\":[\"BILL-1\"],\"amount\":{\"BILL-2\":\"21.00\"},\"duplicate\":[\"BILL-3\"],"
                + "\"extra\":[{\"type\":\"PAYMENT\",\"outTradeNo\":\"CHANNEL-ONLY\",\"tradeNo\":\"CH-1\",\"amount\":\"7.70\"},"
                + "{\"type\":\"REFUND\",\"outTradeNo\":\"BILL-3\",\"tradeNo\":\"CH-2\",\"outRefundNo\":\"CH-RF\",\"amount\":\"1.00\"}]}");

        List<String> lines = downloadLines(today);
        List<String> rows = dataRows(lines);
        assertThat(rows.stream().filter(row -> row.contains("BILL-1,"))).isEmpty();                       // 本地单边
        assertThat(rows.stream().filter(row -> row.contains("CHANNEL-ONLY"))).hasSize(1);                 // 渠道单边
        assertThat(rows.stream().filter(row -> row.contains(",BILL-2,交易,")).findFirst().orElseThrow())
                .contains(",21.00,21.00,");                                                               // 金额差异
        assertThat(rows.stream().filter(row -> row.contains(",BILL-3,交易,"))).hasSize(2);               // 重复交易
        assertThat(rows.stream().filter(row -> row.contains("CH-RF"))).hasSize(1);                        // 状态差异：渠道多一笔退款
        // 汇总按差异后的明细计算，保持自洽（解析层不会因此拒收）：21 + 3 + 3 + 7.7
        assertThat(lines).contains("#交易合计：4笔，合计金额：34.70元", "#退款合计：2笔，合计金额：6.00元");

        client.reset();
        assertThat(dataRows(downloadLinesAfterReset())).isEmpty();
    }

    private List<String> downloadLinesAfterReset() {
        // reset 清空交易与差异后当日无交易 → 链接查询返回 NO_BILL_DATA，不应还有明细
        AlipayTestClient.Result url = client.billDownloadUrl(today);
        assertThat(url.inner(RESPONSE).path("sub_code").asString()).isEqualTo("NO_BILL_DATA");
        return List.of();
    }

    @Test
    void fetchModesMapToTheSubCodesRhnClassifies() throws Exception {
        payTwoAndRefundOne();
        String[][] cases = {{"NOT_READY", "BILL_NOT_READY"}, {"NOT_FOUND", "BILL_NOT_EXIST"}, {"NO_DATA", "NO_BILL_DATA"},
                {"AUTH_FAILED", "isv.insufficient-isv-permissions"}, {"RATE_LIMITED", "USER_RATE_LIMIT"},
                {"UNSUPPORTED", "TYPE_NOT_SUPPORTED"}};
        for (String[] item : cases) {
            putAnomalies("{\"fetch\":\"" + item[0] + "\"}");
            AlipayTestClient.Result result = client.billDownloadUrl(today);
            assertThat(result.signValid(RESPONSE)).isTrue();
            assertThat(result.inner(RESPONSE).path("code").asString()).isNotEqualTo("10000");
            assertThat(result.inner(RESPONSE).path("sub_code").asString()).isEqualTo(item[1]);
        }
        putAnomalies("{\"fetch\":\"AVAILABLE\"}");
        assertThat(client.billDownloadUrl(today.substring(0, 4) + "-01-01").inner(RESPONSE).path("sub_code").asString())
                .isEqualTo("NO_BILL_DATA");
        assertThat(client.billDownloadUrl(LocalDate.now().plusDays(3).toString()).inner(RESPONSE).path("sub_code").asString())
                .isEqualTo("BILL_NOT_READY");
    }

    @Test
    void corruptModeBreaksFooterTotalsAndBadAnomalyPatchIsRejected() throws Exception {
        payTwoAndRefundOne();
        putAnomalies("{\"fetch\":\"CORRUPT\"}");
        assertThat(downloadLines(today)).contains("#交易合计：3笔，合计金额：30.00元");

        HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock/alipay/bill/anomalies"))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString("{\"fetch\":\"BOGUS\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(bad.statusCode()).isEqualTo(400);
        JsonNode view = JsonMapper.builder().build().readTree(http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/mock/alipay/bill/" + today)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
        assertThat(view.path("rows").size()).isEqualTo(3);
    }
}
