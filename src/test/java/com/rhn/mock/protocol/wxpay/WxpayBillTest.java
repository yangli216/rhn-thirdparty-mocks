package com.rhn.mock.protocol.wxpay;

import com.rhn.mock.protocol.wxpay.support.WxpayTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 微信对账单 pay/downloadbill：CSV 格式（对齐 RHN WechatStatementParser）与差异构造。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WxpayBillTest {
    @LocalServerPort
    int port;

    WxpayTestClient client;
    final HttpClient http = HttpClient.newHttpClient();
    final String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

    @BeforeEach
    void setUp() {
        client = new WxpayTestClient(port);
        client.reset();
    }

    @AfterEach
    void tearDown() {
        client.reset();
    }

    private void paySeedData() {
        client.micropay("WX-BILL-1", "1000", "134567890123456789");
        WxpayTestClient.Result second = client.micropay("WX-BILL-2", "2000", "134567890123456789");
        assertThat(client.refund(second.value("transaction_id"), "WX-RF-1", "500", "2000").value("result_code")).isEqualTo("SUCCESS");
    }

    private void putAnomalies(String json) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock/wxpay/bill/anomalies"))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private static List<String[]> cells(String csv) {
        return Arrays.stream(csv.split("\n")).map(line -> line.split(",", -1)).toList();
    }

    private static String clean(String cell) {
        return cell.startsWith("`") ? cell.substring(1) : cell;
    }

    @Test
    void billHasBacktickRowsAndSummaryMatchingDetails() {
        paySeedData();
        WxpayTestClient.Result bill = client.downloadBill(today);
        assertThat(bill.status()).isEqualTo(200);
        assertThat(bill.body()).doesNotStartWith("<");
        List<String[]> rows = cells(bill.body());
        String[] header = rows.get(0);
        assertThat(header).hasSize(27).contains("微信订单号", "商户订单号", "交易状态", "微信退款单号", "申请退款金额", "公众账号ID");
        // 3 条明细 + 汇总表头 + 汇总行
        assertThat(rows).hasSize(1 + 3 + 2);
        assertThat(rows.subList(1, 4)).allSatisfy(row -> {
            assertThat(row).hasSameSizeAs(header);
            assertThat(row[0]).startsWith("`");
        });
        List<String> head = Arrays.asList(header);
        String[] refundRow = rows.stream().filter(row -> row.length == 27 && clean(row[9]).equals("REFUND")).findFirst().orElseThrow();
        assertThat(clean(refundRow[head.indexOf("商户退款单号")])).isEqualTo("WX-RF-1");
        assertThat(clean(refundRow[head.indexOf("申请退款金额")])).isEqualTo("5.00");
        assertThat(clean(refundRow[head.indexOf("退款状态")])).isEqualTo("SUCCESS");
        assertThat(clean(refundRow[head.indexOf("商户号")])).isEqualTo(WxpayTestClient.MCH_ID);
        assertThat(clean(refundRow[head.indexOf("公众账号ID")])).isEqualTo(WxpayTestClient.APP_ID);

        assertThat(rows.get(4)[0]).isEqualTo("总交易单数");
        String[] summary = rows.get(5);
        assertThat(clean(summary[0])).isEqualTo("3");
        assertThat(clean(summary[1])).isEqualTo("30.00");  // 应结订单总金额
        assertThat(clean(summary[2])).isEqualTo("5.00");   // 退款总金额
        assertThat(clean(summary[5])).isEqualTo("30.00");  // 订单总金额
        assertThat(clean(summary[6])).isEqualTo("5.00");   // 申请退款总金额
    }

    @Test
    void differencesAreConstructible() throws Exception {
        paySeedData();
        putAnomalies("{\"omit\":[\"WX-BILL-1\"],\"amount\":{\"WX-BILL-2\":\"21.00\"},\"duplicate\":[\"WX-RF-1\"],"
                + "\"refundStatus\":{\"WX-RF-1\":\"PROCESSING\"},"
                + "\"extra\":[{\"type\":\"PAYMENT\",\"outTradeNo\":\"WX-CH-ONLY\",\"tradeNo\":\"4200CH1\",\"amount\":\"7.70\"}]}");
        String csv = client.downloadBill(today).body();
        assertThat(csv).doesNotContain("WX-BILL-1");
        assertThat(csv).contains("WX-CH-ONLY").contains("`21.00");
        List<String[]> rows = cells(csv);
        assertThat(rows.stream().filter(row -> row.length == 27 && clean(row[9]).equals("REFUND")))
                .hasSize(2).allSatisfy(row -> assertThat(clean(row[19])).isEqualTo("PROCESSING"));
        String[] summary = rows.get(rows.size() - 1);
        assertThat(clean(summary[0])).isEqualTo("4");
        assertThat(clean(summary[5])).isEqualTo("28.70"); // 21 + 7.7
        assertThat(clean(summary[6])).isEqualTo("10.00"); // 退款重复两次
    }

    @Test
    void fetchModesAndEmptyBill() throws Exception {
        paySeedData();
        String[][] cases = {{"NOT_READY", "20003"}, {"NOT_FOUND", "20002"}, {"AUTH_FAILED", "SIGNERROR"}, {"RATE_LIMITED", "20008"}};
        for (String[] item : cases) {
            putAnomalies("{\"fetch\":\"" + item[0] + "\"}");
            WxpayTestClient.Result result = client.downloadBill(today);
            assertThat(result.value("return_code")).isEqualTo("FAIL");
            assertThat(result.value("error_code")).isEqualTo(item[1]);
        }
        putAnomalies("{\"fetch\":\"NO_DATA\"}");
        List<String[]> empty = cells(client.downloadBill(today).body());
        assertThat(empty).hasSize(3);
        assertThat(clean(empty.get(2)[0])).isEqualTo("0");

        putAnomalies("{\"fetch\":\"CORRUPT\"}");
        List<String[]> corrupt = cells(client.downloadBill(today).body());
        assertThat(clean(corrupt.get(corrupt.size() - 1)[0])).isEqualTo("4"); // 汇总笔数与 3 条明细对不上

        putAnomalies("{\"fetch\":\"AVAILABLE\"}");
        assertThat(client.downloadBill(LocalDate.now().plusDays(2).format(DateTimeFormatter.BASIC_ISO_DATE)).value("error_code"))
                .isEqualTo("20003");
        assertThat(client.downloadBill("2020-01-01").value("error_code")).isEqualTo("20001");
    }

    @Test
    void badSignatureIsRejectedForBillDownloadToo() {
        WxpayTestClient.Result result = client.postRaw("/pay/downloadbill",
                "<xml><appid>x</appid><mch_id>y</mch_id><bill_date>" + today + "</bill_date><bill_type>ALL</bill_type><sign>BAD</sign></xml>");
        assertThat(result.value("return_code")).isEqualTo("FAIL");
    }
}
