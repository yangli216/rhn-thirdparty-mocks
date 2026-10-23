package com.rhn.mock.protocol.chs.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按 {@code HttpChsNationalInsuranceClient} 真实发出的报文形状手工组包的测试客户端，
 * 不依赖 rhn 模块，用于端到端验证 Mock 服务。
 */
public class ChsTestClient {
    public static final String DEFAULT_OPERATOR = "MOCKOP";
    public static final String BUSINESS_OPERATOR = "DOC1";

    private static final String FIXMEDINS_CODE = "MOCK0001";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<String, String> signNos = new HashMap<>();
    private final String baseUrl;
    private final String token;

    public ChsTestClient(int port) {
        this(port, "mock-token");
    }

    public ChsTestClient(int port, String token) {
        this.baseUrl = "http://127.0.0.1:" + port;
        this.token = token;
    }

    // ==================== 交易 ====================

    public ObjectNode signInInput(String opter) {
        ObjectNode signIn = object();
        signIn.put("opter_no", opter);
        signIn.put("mac", "00-00-00-00-00-01");
        signIn.put("ip", "127.0.0.1");
        ObjectNode input = object();
        input.set("signIn", signIn);
        return input;
    }

    public Result signIn(String opter) {
        Result result = post(write(envelope("9001", opter, "", signInInput(opter))));
        if (result.infcode() == 0) {
            signNos.put(opter, result.output().path("signinoutb").path("sign_no").asString());
        }
        return result;
    }

    public Result person(String certNo, String certType, String psnName) {
        ObjectNode data = object();
        data.put("mdtrt_cert_type", certType);
        data.put("mdtrt_cert_no", certNo);
        data.put("card_sn", "");
        data.put("begntime", now());
        data.put("psn_cert_type", "02".equals(certType) ? "01" : "");
        data.put("certno", "02".equals(certType) ? certNo : "");
        data.put("psn_name", psnName);
        ObjectNode input = object();
        input.set("data", data);
        return business("1101", DEFAULT_OPERATOR, "", input);
    }

    public Result register(String psnNo, String insutype, String certType, String certNo, String opter) {
        ObjectNode data = object();
        data.put("psn_no", psnNo);
        data.put("insutype", insutype);
        data.put("begntime", now());
        data.put("mdtrt_cert_type", certType);
        data.put("mdtrt_cert_no", certNo);
        data.put("ipt_otp_no", "REG-1");
        data.put("atddr_no", opter);
        data.put("dr_name", opter);
        data.put("dept_code", "D01");
        data.put("dept_name", "D01");
        data.put("caty", "D01");
        ObjectNode input = object();
        input.set("data", data);
        return business("2201", opter, "360100", input);
    }

    public Result diagnosis(String mdtrtId, String psnNo, String opter) {
        ObjectNode info = object();
        info.put("mdtrt_id", mdtrtId);
        info.put("psn_no", psnNo);
        info.put("med_type", "11");
        info.put("begntime", now());
        info.put("main_cond_dscr", "急性上呼吸道感染");
        info.put("dise_codg", "J06.900");
        info.put("dise_name", "急性上呼吸道感染");
        ArrayNode diseinfo = array();
        ObjectNode row = diseinfo.addObject();
        row.put("diag_type", "1");
        row.put("diag_srt_no", 1);
        row.put("diag_code", "J06.900");
        row.put("diag_name", "急性上呼吸道感染");
        row.put("diag_dept", "D01");
        row.put("dise_dor_no", opter);
        row.put("dise_dor_name", opter);
        row.put("diag_time", now());
        ObjectNode input = object();
        input.set("mdtrtinfo", info);
        input.set("diseinfo", diseinfo);
        return business("2203A", opter, "360100", input);
    }

    public record Line(String feedetlSn, String medListCodg, BigDecimal cnt, BigDecimal pric, BigDecimal amount) {
    }

    /** 固定三条明细：甲类 X、乙类 Z、丙类 T。 */
    public static List<Line> defaultLines() {
        return List.of(
                new Line("1", "XA01", new BigDecimal("2"), new BigDecimal("50.00"), new BigDecimal("100.00")),
                new Line("2", "ZA01", new BigDecimal("1"), new BigDecimal("50.00"), new BigDecimal("50.00")),
                new Line("3", "TA01", new BigDecimal("3"), new BigDecimal("10.00"), new BigDecimal("30.00")));
    }

    public static BigDecimal defaultTotal() {
        BigDecimal total = BigDecimal.ZERO;
        for (Line line : defaultLines()) total = total.add(line.amount());
        return total;
    }

    public Result feedetails(String mdtrtId, String psnNo, String chrgBchno, String opter, List<Line> lines) {
        ArrayNode feedetail = array();
        for (Line line : lines) {
            ObjectNode row = feedetail.addObject();
            row.put("feedetl_sn", line.feedetlSn());
            row.put("mdtrt_id", mdtrtId);
            row.put("psn_no", psnNo);
            row.put("chrg_bchno", chrgBchno);
            row.put("rxno", line.feedetlSn());
            row.put("fee_ocur_time", now());
            row.put("med_list_codg", line.medListCodg());
            row.put("medins_list_codg", "LOCAL-" + line.feedetlSn());
            row.put("det_item_fee_sumamt", line.amount());
            row.put("cnt", line.cnt());
            row.put("pric", line.pric());
            row.put("bilg_dept_codg", "D01");
            row.put("bilg_dept_name", "D01");
            row.put("bilg_dr_codg", opter);
            row.put("bilg_dr_name", opter);
            row.put("hosp_appr_flag", "2");
        }
        ObjectNode input = object();
        input.set("feedetail", feedetail);
        return business("2204", opter, "360100", input);
    }

    public Result preSettle(String mdtrtId, String psnNo, String insutype, String certType, String certNo,
                            BigDecimal total, String chrgBchno, boolean acctUsed, String opter) {
        ObjectNode data = object();
        data.put("mdtrt_id", mdtrtId);
        data.put("psn_no", psnNo);
        data.put("insutype", insutype);
        data.put("mdtrt_cert_type", certType);
        data.put("mdtrt_cert_no", certNo);
        data.put("med_type", "11");
        data.put("medfee_sumamt", total);
        data.put("psn_setlway", "01");
        data.put("chrg_bchno", chrgBchno);
        data.put("acct_used_flag", acctUsed ? "1" : "0");
        ObjectNode input = object();
        input.set("data", data);
        return business("2206", opter, "360100", input);
    }

    public Result settle(String mdtrtId, String psnNo, String insutype, String chrgBchno, boolean acctUsed, String opter) {
        ObjectNode data = object();
        data.put("mdtrt_id", mdtrtId);
        data.put("psn_no", psnNo);
        data.put("insutype", insutype);
        data.put("chrg_bchno", chrgBchno);
        data.put("acct_used_flag", acctUsed ? "1" : "0");
        ObjectNode input = object();
        input.set("data", data);
        return business("2207", opter, "360100", input);
    }

    public Result reverse(String setlId, String mdtrtId, String psnNo, String opter) {
        ObjectNode data = object();
        data.put("setl_id", setlId);
        data.put("mdtrt_id", mdtrtId);
        data.put("psn_no", psnNo);
        ObjectNode input = object();
        input.set("data", data);
        return business("2208", opter, "360100", input);
    }

    public Result cancelFeedetails(String mdtrtId, String psnNo, String chrgBchno, String opter) {
        ObjectNode data = object();
        data.put("mdtrt_id", mdtrtId);
        data.put("chrg_bchno", chrgBchno);
        data.put("psn_no", psnNo);
        ObjectNode input = object();
        input.set("data", data);
        return business("2205", opter, "360100", input);
    }

    public Result cancelRegistration(String mdtrtId, String psnNo, String opter) {
        ObjectNode data = object();
        data.put("mdtrt_id", mdtrtId);
        data.put("psn_no", psnNo);
        data.put("ipt_otp_no", "REG-1");
        ObjectNode input = object();
        input.set("data", data);
        return business("2202", opter, "360100", input);
    }

    // ==================== 预结算前链路 ====================

    public record PreFlow(String certNo, String psnNo, String insutype, String mdtrtId, String chrgBchno,
                          Result person, Result register, Result diagnosis, Result feedetails, Result preSettle) {
        public boolean reachedPreSettle() {
            return preSettle != null;
        }
    }

    /** 依次执行 1101→2201→2203A→2204→2206，任一步失败即提前停止并在结果中保留已执行的步骤。 */
    public PreFlow toPreSettle(String certNo, boolean acctUsed) {
        String chrgBchno = "B001";
        Result person = person(certNo, "02", "张三");
        if (person.infcode() != 0) return new PreFlow(certNo, null, null, null, chrgBchno, person, null, null, null, null);
        String psnNo = person.output().path("baseinfo").path("psn_no").asString();
        String insutype = activeInsutype(person.output().path("insuinfo"));
        Result register = register(psnNo, insutype, "02", certNo, BUSINESS_OPERATOR);
        if (register.infcode() != 0) return new PreFlow(certNo, psnNo, insutype, null, chrgBchno, person, register, null, null, null);
        String mdtrtId = register.output().path("data").path("mdtrt_id").asString();
        Result diagnosis = diagnosis(mdtrtId, psnNo, BUSINESS_OPERATOR);
        if (diagnosis.infcode() != 0) {
            return new PreFlow(certNo, psnNo, insutype, mdtrtId, chrgBchno, person, register, diagnosis, null, null);
        }
        Result feedetails = feedetails(mdtrtId, psnNo, chrgBchno, BUSINESS_OPERATOR, defaultLines());
        if (feedetails.infcode() != 0) {
            return new PreFlow(certNo, psnNo, insutype, mdtrtId, chrgBchno, person, register, diagnosis, feedetails, null);
        }
        Result pre = preSettle(mdtrtId, psnNo, insutype, "02", certNo, defaultTotal(), chrgBchno, acctUsed, BUSINESS_OPERATOR);
        return new PreFlow(certNo, psnNo, insutype, mdtrtId, chrgBchno, person, register, diagnosis, feedetails, pre);
    }

    /** 与客户端一致的参保选取：优先 psn_insu_stas=1，否则取首条。 */
    public static String activeInsutype(JsonNode insuinfo) {
        if (insuinfo == null || !insuinfo.isArray() || insuinfo.isEmpty()) return null;
        for (JsonNode row : insuinfo) if ("1".equals(row.path("psn_insu_stas").asString())) return row.path("insutype").asString();
        return insuinfo.get(0).path("insutype").asString();
    }

    // ==================== 管理端点 ====================

    public void reset() {
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/mock/reset"))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
        signNos.clear();
    }

    public Result get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
    }

    public Result putConfig(String body) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/mock/config"))
                .header("Content-Type", "application/json;charset=UTF-8")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    /** 把运行时可改配置恢复为默认值，避免测试间相互影响。 */
    public void resetConfig() {
        putConfig("{\"ipPoolRatio\":0.80,\"preselfRatio\":0.15,\"ipPoolRatioB\":0.70,\"xToken\":\"mock-token\","
                + "\"signInRequired\":true,\"diagnosisRequired\":false,\"forceDelayMs\":0,\"slowDelayMs\":31000,\"forceInfcode\":null,"
                + "\"forceErrMsg\":null,\"itemLevelPrefixes\":{\"X\":\"01\",\"Z\":\"02\",\"T\":\"03\"}}");
    }

    // ==================== 组包与传输 ====================

    public String envelopeBody(String infno, String opter, String insuplc, ObjectNode input) {
        return write(envelope(infno, opter, insuplc, input));
    }

    public ObjectNode envelope(String infno, String opter, String insuplc, ObjectNode input) {
        ObjectNode root = object();
        root.put("infno", infno);
        root.put("msgid", FIXMEDINS_CODE + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", Math.floorMod(sequence.incrementAndGet(), 10000)));
        root.put("mdtrtarea_admvs", "360100");
        root.put("insuplc_admdvs", insuplc == null ? "" : insuplc);
        root.put("recer_sys_code", "MOCKSYS");
        root.put("dev_no", "");
        root.put("dev_safe_info", "");
        root.put("cainfo", "");
        root.put("signtype", "");
        root.put("infver", "V1.0");
        root.put("opter_type", "1");
        root.put("opter", opter);
        root.put("opter_name", opter);
        root.put("inf_time", now());
        root.put("fixmedins_code", FIXMEDINS_CODE);
        root.put("fixmedins_name", "联调测试医院");
        root.put("sign_no", signNos.getOrDefault(opter, ""));
        root.set("input", input);
        return root;
    }

    public Result business(String infno, String opter, String insuplc, ObjectNode input) {
        if (!signNos.containsKey(opter)) signIn(opter);
        return post(envelopeBody(infno, opter, insuplc, input));
    }

    public Result post(String body) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/hsa/api"))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("X-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    public Result postWithoutToken(String body) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/hsa/api"))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    public ObjectNode object() {
        return mapper.createObjectNode();
    }

    public ArrayNode array() {
        return mapper.createArrayNode();
    }

    public String write(JsonNode node) {
        return mapper.writeValueAsString(node);
    }

    public JsonNode read(String body) {
        return mapper.readTree(body);
    }

    public Result send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = null;
            try {
                json = mapper.readTree(response.body());
            } catch (RuntimeException ignored) {
                // 非 JSON 回执（TEST-BADJSON）保持 null
            }
            return new Result(response.statusCode(), response.body(), json);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("调用 Mock 服务失败: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 Mock 服务被中断", exception);
        }
    }

    /** 一次调用的结果；json 为 null 表示回执不是 JSON。 */
    public record Result(int status, String body, JsonNode json) {
        public int infcode() {
            if (json == null) return Integer.MIN_VALUE;
            String value = json.path("infcode").asString();
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                return Integer.MIN_VALUE;
            }
        }

        public boolean succeeded() {
            return status == 200 && infcode() == 0;
        }

        public JsonNode output() {
            return json == null ? MissingNode.getInstance() : json.path("output");
        }

        public String errMsg() {
            return json == null ? null : json.path("err_msg").asString();
        }
    }

    private static String now() {
        return LocalDateTime.now().format(TIME);
    }
}
