package com.rhn.mock.protocol.chs;

import com.rhn.mock.core.ExchangeLog;
import com.rhn.mock.core.MockHttpResponse;
import com.rhn.mock.core.MockRawResponse;
import com.rhn.mock.core.MockRejection;
import com.rhn.mock.core.JsonNodes;
import com.rhn.mock.protocol.chs.config.ChsMockConfig;
import com.rhn.mock.protocol.chs.config.ChsMockConfigService;
import com.rhn.mock.protocol.chs.config.ChsMockProperties;
import com.rhn.mock.protocol.chs.web.EnvelopeKeys;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Objects;

/**
 * 医保 Mock 交易引擎：报文外壳校验、X-Token、msgid 唯一、签到校验、全局故障开关与 9 个交易的分派。
 * 业务成功与业务拒绝统一 HTTP 200（{@code infcode=0/-1}）；TEST-HTTP500 / TEST-BADJSON 走 HTTP 层异常。
 */
@Component
public class ChsMockEngine {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final List<String> BUSINESS_TRANSACTIONS =
            List.of("2201", "2202", "2203A", "2204", "2205", "2206", "2207", "2208");

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final ChsMockConfigService configs;
    private final ChsMockState state;
    private final ScenarioCatalog scenarios;
    private final SettlementCalculator calculator;
    private final ExchangeLog log;
    /** 身份证号（规范化：去空格、大写）→ TEST-* 测试人员证件号。 */
    private final Map<String, String> certAliases = new HashMap<>();

    public ChsMockEngine(ChsMockConfigService configs, ChsMockState state, ScenarioCatalog scenarios,
                         SettlementCalculator calculator, ExchangeLog log, ChsMockProperties properties) {
        this.configs = configs;
        this.state = state;
        this.scenarios = scenarios;
        this.calculator = calculator;
        this.log = log;
        if (properties.certAliases() != null) {
            properties.certAliases().forEach((idNo, persona) -> {
                if (idNo != null && persona != null && !persona.isBlank()) {
                    certAliases.put(normalizeCert(idNo), persona.trim());
                }
            });
        }
    }

    private static String normalizeCert(String certNo) {
        return certNo.trim().toUpperCase(Locale.ROOT);
    }

    /** 证件号命中别名映射则返回目标 TEST-* 人员，否则原样返回。 */
    private String resolvePersona(String certNo) {
        return certAliases.getOrDefault(normalizeCert(certNo), certNo);
    }

    public MockHttpResponse handle(String rawBody, String xTokenHeader) {
        ChsMockConfig config = configs.current();
        if (!config.xToken().isBlank() && !config.xToken().equals(xTokenHeader)) {
            return MockHttpResponse.raw(401, "{\"error\":\"X-Token 缺失或不匹配\"}", "application/json;charset=UTF-8");
        }
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (RuntimeException failure) {
            return MockHttpResponse.raw(400, "{\"error\":\"请求体不是合法 JSON\"}", "application/json;charset=UTF-8");
        }
        if (root == null || !root.isObject()) {
            return MockHttpResponse.raw(400, "{\"error\":\"请求体必须是 JSON 对象\"}", "application/json;charset=UTF-8");
        }
        String msgid = JsonNodes.text(root, "msgid");
        String infno = JsonNodes.text(root, "infno");
        String now = now();
        MockHttpResponse respond;
        try {
            EnvelopeKeys.validate(root);
            if (!state.markMsgId(msgid)) throw new MockRejection("msgid 重复：" + msgid);
            String opter = JsonNodes.text(root, "opter");
            if (!"9001".equals(infno) && config.signInRequired()
                    && !state.matchesSignNo(opter, JsonNodes.text(root, "sign_no"))) {
                throw new MockRejection("签到无效或未签到，请先执行 9001 签到");
            }
            if (config.forceDelayMs() > 0) sleep(config.forceDelayMs());
            if (config.forceInfcode() != null) {
                respond = MockHttpResponse.json(config.forceInfcode() == 0
                        ? success(msgid, NODES.objectNode(), now)
                        : reject(msgid, config.forceErrMsg() == null ? "强制应答" : config.forceErrMsg(), now));
            } else {
                ObjectNode output = dispatch(root, infno, config);
                respond = MockHttpResponse.json(success(msgid, output, now));
                expireSignIfNeeded(root, infno, opter);
            }
        } catch (MockRawResponse raw) {
            respond = MockHttpResponse.raw(raw.status(), raw.body(), raw.contentType());
        } catch (MockRejection rejected) {
            respond = MockHttpResponse.json(reject(msgid, rejected.getMessage(), now));
        }
        log.add(new ExchangeLog.Entry(now, ChsProtocol.ID, infno, msgid, respond.httpStatus(), rawBody, respond.body()));
        return respond;
    }

    private ObjectNode dispatch(JsonNode root, String infno, ChsMockConfig config) {
        return switch (infno) {
            case "9001" -> signIn(root);
            case "1101" -> person(root);
            case "2201" -> registration(root);
            case "2202" -> cancelRegistration(root);
            case "2203A" -> diagnosis(root);
            case "2204" -> feedetails(root, config);
            case "2205" -> cancelFeedetails(root);
            case "2206" -> preSettle(root, config);
            case "2207" -> settle(root);
            case "2208" -> reversal(root);
            default -> throw new MockRejection("不支持的交易类型 infno=" + infno);
        };
    }

    // ==================== 9001 签到 ====================

    private ObjectNode signIn(JsonNode root) {
        ObjectNode signIn = object(input(root).get("signIn"));
        if (signIn == null) throw new MockRejection("缺少 input.signIn");
        String opterNo = JsonNodes.text(signIn, "opter_no");
        if (opterNo == null) throw new MockRejection("缺少 input.signIn.opter_no");
        String signNo = state.nextId("SIGN", 6);
        state.putSignNo(opterNo, signNo);
        ObjectNode output = NODES.objectNode();
        ObjectNode body = output.putObject("signinoutb");
        body.put("sign_no", signNo);
        body.put("sign_time", now());
        return output;
    }

    // ==================== 1101 人员信息 ====================

    private ObjectNode person(JsonNode root) {
        ObjectNode data = object(input(root).get("data"));
        if (data == null) throw new MockRejection("缺少 input.data");
        String certNo = JsonNodes.text(data, "mdtrt_cert_no");
        if (certNo == null) throw new MockRejection("缺少 input.data.mdtrt_cert_no");
        String persona = resolvePersona(certNo);
        ScenarioCatalog.Fixture fixture = scenarios.fixtureFor(persona);
        if (fixture.behavior() == ScenarioCatalog.Behavior.NO_INSU) throw new MockRejection("无参保信息");
        ScenarioCatalog.InsuRow active = fixture.activeRow();
        String certType = JsonNodes.text(data, "mdtrt_cert_type");
        String psnName = JsonNodes.text(data, "psn_name");
        String psnNo = "PSN-" + persona;
        state.putPerson(new ChsMockState.PersonSession(psnNo, certType, certNo, active.insutype(),
                fixture.behavior(), ScenarioCatalog.money(active.balc())));

        ObjectNode output = NODES.objectNode();
        ObjectNode base = output.putObject("baseinfo");
        base.put("psn_no", psnNo);
        base.put("certno", certNo);
        base.put("psn_name", psnName == null ? "测试人员" : psnName);
        base.put("gend", "1");
        base.put("brdy", "1988-01-01");
        ArrayNode insuinfo = output.putArray("insuinfo");
        for (ScenarioCatalog.InsuRow row : fixture.rows()) {
            ObjectNode item = insuinfo.addObject();
            item.put("insutype", row.insutype());
            item.put("psn_insu_stas", row.stas());
            if (row.balc() != null) item.put("balc", row.balc());
            item.put("psn_type", row.psnType());
            item.put("insuplc_admdvs", row.admdvs());
        }
        return output;
    }

    // ==================== 2201 就诊登记 ====================

    private ObjectNode registration(JsonNode root) {
        ObjectNode data = requiredObject(input(root).get("data"), "input.data");
        String psnNo = required(data, "psn_no");
        ChsMockState.PersonSession person = state.person(psnNo);
        if (person == null) throw new MockRejection("医保人员编号未经 1101 鉴权");
        String certType = JsonNodes.text(data, "mdtrt_cert_type");
        String certNo = JsonNodes.text(data, "mdtrt_cert_no");
        if (!Objects.equals(certType, person.certType()) || !Objects.equals(certNo, person.certNo())) {
            throw new MockRejection("就诊凭证与 1101 鉴权结果不一致");
        }
        String insutype = required(data, "insutype");
        if (!insutype.equals(person.insutype())) throw new MockRejection("险种与参保信息不一致");
        rejectIfSuspended(person);
        String mdtrtId = state.nextId("MDT", 8);
        state.putRegistration(new ChsMockState.Registration(mdtrtId, psnNo, insutype, certType, certNo));
        ObjectNode output = NODES.objectNode();
        output.putObject("data").put("mdtrt_id", mdtrtId);
        return output;
    }

    // ==================== 2202 挂号撤销 ====================

    private ObjectNode cancelRegistration(JsonNode root) {
        ObjectNode data = requiredObject(input(root).get("data"), "input.data");
        String mdtrtId = required(data, "mdtrt_id");
        String psnNo = required(data, "psn_no");
        if (state.isRegistrationCancelled(mdtrtId)) throw new MockRejection("该就诊登记已撤销，不能重复撤销");
        ChsMockState.Registration registration = state.registration(mdtrtId);
        if (registration == null) throw new MockRejection("就诊未登记(2201)，不能撤销");
        if (!psnNo.equals(registration.psnNo())) throw new MockRejection("撤销人员与就诊登记不一致");
        if (state.hasActiveSettlement(mdtrtId)) throw new MockRejection("该就诊已结算且未撤销，请先撤销结算(2208)");
        state.cancelRegistration(mdtrtId);
        return NODES.objectNode();
    }

    // ==================== 2203A 就诊诊断上传 ====================

    private ObjectNode diagnosis(JsonNode root) {
        ObjectNode in = input(root);
        ObjectNode info = object(in.get("mdtrtinfo"));
        if (info == null) info = object(in.get("data"));
        if (info == null) throw new MockRejection("缺少就诊信息 mdtrtinfo");
        String mdtrtId = required(info, "mdtrt_id");
        ChsMockState.Registration registration = state.registration(mdtrtId);
        if (registration == null) throw new MockRejection(unregistered(mdtrtId) + "，不能上传诊断");
        String psnNo = required(info, "psn_no");
        if (!psnNo.equals(registration.psnNo())) throw new MockRejection("就诊登记与人员不一致");
        if (JsonNodes.text(info, "main_cond_dscr") == null || JsonNodes.text(info, "dise_codg") == null) {
            throw new MockRejection("缺少主诊断，不能上传就诊诊断");
        }
        state.markDiagnosis(mdtrtId);
        return NODES.objectNode();
    }

    // ==================== 2204 费用明细上传 ====================

    private ObjectNode feedetails(JsonNode root, ChsMockConfig config) {
        JsonNode detail = input(root).get("feedetail");
        if (detail == null || !detail.isArray() || detail.isEmpty()) throw new MockRejection("缺少费用明细 input.feedetail");
        String mdtrtId = required(requiredObject(detail.get(0), "feedetail[0]"), "mdtrt_id");
        ChsMockState.Registration registration = state.registration(mdtrtId);
        if (registration == null) throw new MockRejection(unregistered(mdtrtId));
        if (config.diagnosisRequired() && !state.hasDiagnosis(mdtrtId)) throw new MockRejection("未上传就诊诊断(2203A)，不能上传费用明细");

        ArrayNode result = NODES.arrayNode();
        List<SettlementCalculator.FeeLine> lines = new ArrayList<>();
        for (JsonNode node : detail) {
            ObjectNode row = requiredObject(node, "feedetail[]");
            String feedetlSn = required(row, "feedetl_sn");
            String rowMdtrtId = required(row, "mdtrt_id");
            if (!rowMdtrtId.equals(mdtrtId)) throw new MockRejection("同一批费用明细的就诊 ID 不一致");
            BigDecimal cnt = requiredDecimal(row, "cnt");
            BigDecimal pric = requiredDecimal(row, "pric");
            BigDecimal amount = requiredDecimal(row, "det_item_fee_sumamt");
            if (amount.compareTo(cnt.multiply(pric)) != 0) {
                throw new MockRejection("费用明细金额与数量×单价不一致(feedetl_sn=" + feedetlSn + ")");
            }
            String level = level(JsonNodes.text(row, "med_list_codg"), config);
            ObjectNode item = result.addObject();
            item.put("feedetl_sn", feedetlSn);
            item.put("chrgitm_lv", level);
            lines.add(new SettlementCalculator.FeeLine(feedetlSn, amount, level));
        }
        state.putFees(mdtrtId, lines);
        ObjectNode output = NODES.objectNode();
        output.set("result", result);
        return output;
    }

    // ==================== 2205 费用明细撤销 ====================

    /** 撤销该就诊已上传的 2204 费用明细及基于其的 2206 预结算；已正式结算且未撤销的须先 2208。 */
    private ObjectNode cancelFeedetails(JsonNode root) {
        ObjectNode data = requiredObject(input(root).get("data"), "input.data");
        String mdtrtId = required(data, "mdtrt_id");
        required(data, "chrg_bchno");
        String psnNo = required(data, "psn_no");
        ChsMockState.Registration registration = state.registration(mdtrtId);
        if (registration == null) throw new MockRejection(unregistered(mdtrtId) + "，不能撤销费用明细");
        if (!psnNo.equals(registration.psnNo())) throw new MockRejection("撤销人员与就诊登记不一致");
        if (state.hasActiveSettlement(mdtrtId)) throw new MockRejection("该就诊已结算且未撤销，请先撤销结算(2208)");
        if (!state.cancelFees(mdtrtId)) throw new MockRejection("该就诊没有可撤销的费用明细(2204)");
        return NODES.objectNode();
    }

    // ==================== 2206 预结算 ====================

    private ObjectNode preSettle(JsonNode root, ChsMockConfig config) {
        ObjectNode data = requiredObject(input(root).get("data"), "input.data");
        String mdtrtId = required(data, "mdtrt_id");
        ChsMockState.Registration registration = state.registration(mdtrtId);
        if (registration == null) throw new MockRejection(unregistered(mdtrtId));
        String psnNo = required(data, "psn_no");
        if (!psnNo.equals(registration.psnNo())) throw new MockRejection("预结算人员与就诊登记不一致");
        String insutype = required(data, "insutype");
        if (!insutype.equals(registration.insutype())) throw new MockRejection("预结算险种与就诊登记不一致");
        rejectIfSuspended(state.person(psnNo));
        List<SettlementCalculator.FeeLine> lines = state.fees(mdtrtId);
        if (lines == null) throw new MockRejection("未上传费用明细(2204)，不能预结算");
        BigDecimal total = requiredDecimal(data, "medfee_sumamt");
        BigDecimal sum = BigDecimal.ZERO;
        for (SettlementCalculator.FeeLine line : lines) sum = sum.add(line.amount());
        if (sum.compareTo(total) != 0) throw new MockRejection("费用总额与费用明细合计不一致");

        ChsMockState.PersonSession person = state.person(psnNo);
        ScenarioCatalog.Behavior behavior = person == null ? ScenarioCatalog.Behavior.NORMAL : person.behavior();
        if (behavior == ScenarioCatalog.Behavior.REJECT_2206) throw new MockRejection("该患者存在未结算记录");
        if (behavior == ScenarioCatalog.Behavior.SLOW) sleep(config.slowDelayMs());

        boolean acctUsed = "1".equals(JsonNodes.text(data, "acct_used_flag"));
        BigDecimal balance = person == null ? null : person.balc();
        SettlementCalculator.Settlement settlement = calculator.calculate(
                state.nextId("PRE", 8), total, lines, acctUsed, insutype, balance, config);
        if (behavior == ScenarioCatalog.Behavior.IMBALANCE) settlement = settlement.nonBalanced();
        state.putPreSettlement(mdtrtId, settlement);

        ObjectNode output = NODES.objectNode();
        output.set("setlinfo", setlInfo(settlement, behavior == ScenarioCatalog.Behavior.MISSING_AMT, null));
        return output;
    }

    // ==================== 2207 结算 ====================

    private ObjectNode settle(JsonNode root) {
        ObjectNode data = requiredObject(input(root).get("data"), "input.data");
        String mdtrtId = required(data, "mdtrt_id");
        ChsMockState.Registration registration = state.registration(mdtrtId);
        if (registration == null) throw new MockRejection(unregistered(mdtrtId));
        String psnNo = required(data, "psn_no");
        if (!psnNo.equals(registration.psnNo())) throw new MockRejection("结算人员与就诊登记不一致");
        String insutype = required(data, "insutype");
        if (!insutype.equals(registration.insutype())) throw new MockRejection("结算险种与就诊登记不一致");
        SettlementCalculator.Settlement pre = state.preSettlement(mdtrtId);
        if (pre == null) {
            if (state.hasActiveSettlement(mdtrtId)) throw new MockRejection("该笔已结算，重复结算被拒绝");
            throw new MockRejection("未完成预结算(2206)，不能结算");
        }

        ChsMockState.PersonSession person = state.person(psnNo);
        ScenarioCatalog.Behavior behavior = person == null ? ScenarioCatalog.Behavior.NORMAL : person.behavior();
        if (behavior == ScenarioCatalog.Behavior.HTTP500) {
            throw new MockRawResponse(500, "{\"error\":\"simulated 2207 http 500\"}", "application/json;charset=UTF-8");
        }
        if (behavior == ScenarioCatalog.Behavior.BADJSON) {
            throw new MockRawResponse(200, "<html>not a json response</html>", "text/plain;charset=UTF-8");
        }

        String setlId = state.nextId("SETL", 8);
        SettlementCalculator.Settlement settlement = pre.withSetlId(setlId);
        if (behavior == ScenarioCatalog.Behavior.IMBALANCE) settlement = settlement.nonBalanced();
        state.recordSettlement(setlId, mdtrtId, psnNo);

        ObjectNode output = NODES.objectNode();
        output.set("setlinfo", setlInfo(settlement, false, now()));
        return output;
    }

    // ==================== 2208 结算撤销 ====================

    private ObjectNode reversal(JsonNode root) {
        ObjectNode data = requiredObject(input(root).get("data"), "input.data");
        String setlId = required(data, "setl_id");
        ChsMockState.Settled settled = state.settled(setlId);
        if (settled == null) throw new MockRejection("原结算流水不存在或未结算");
        if (settled.reversed()) throw new MockRejection("该结算已撤销，不能重复撤销");
        if (!required(data, "mdtrt_id").equals(settled.mdtrtId())) throw new MockRejection("撤销的就诊 ID 与原结算不一致");
        if (!required(data, "psn_no").equals(settled.psnNo())) throw new MockRejection("撤销的人员编号与原结算不一致");
        state.markReversed(setlId);
        ObjectNode output = NODES.objectNode();
        output.putObject("setlinfo").put("setl_id", state.nextId("REV", 8));
        return output;
    }

    // ==================== TEST-SIGN-EXPIRE：首个业务交易后使 sign_no 失效 ====================

    private void expireSignIfNeeded(JsonNode root, String infno, String opter) {
        if (!BUSINESS_TRANSACTIONS.contains(infno)) return;
        String psnNo = psnNoOf(root);
        ChsMockState.PersonSession person = state.person(psnNo);
        if (person != null && person.behavior() == ScenarioCatalog.Behavior.SIGN_EXPIRE && state.markSignExpired(psnNo)) {
            state.removeSignNo(opter);
        }
    }

    private static String psnNoOf(JsonNode root) {
        JsonNode in = root.get("input");
        if (in == null || !in.isObject()) return null;
        String psnNo = JsonNodes.text(in.get("data"), "psn_no");
        if (psnNo != null) return psnNo;
        psnNo = JsonNodes.text(in.get("mdtrtinfo"), "psn_no");
        if (psnNo != null) return psnNo;
        JsonNode feedetail = in.get("feedetail");
        if (feedetail != null && feedetail.isArray() && !feedetail.isEmpty()) {
            return JsonNodes.text(feedetail.get(0), "psn_no");
        }
        return null;
    }

    // ==================== 回执构造 ====================

    private ObjectNode setlInfo(SettlementCalculator.Settlement settlement, boolean omitAccountPay, String setlTime) {
        ObjectNode info = NODES.objectNode();
        info.put("setl_id", settlement.setlId());
        info.put("medfee_sumamt", money(settlement.medfeeSumamt()));
        info.put("fund_pay_sumamt", money(settlement.fundPaySumamt()));
        info.put("hifp_pay", money(settlement.hifpPay()));
        info.put("hifob_pay", money(settlement.hifobPay()));
        if (!omitAccountPay) info.put("acct_pay", money(settlement.acctPay()));
        info.put("psn_cash_pay", money(settlement.psnCashPay()));
        info.put("inscp_scp_amt", money(settlement.inscpScpAmt()));
        info.put("fulamt_ownpay_amt", money(settlement.fulamtOwnpayAmt()));
        info.put("preselfpay_amt", money(settlement.preselfpayAmt()));
        if (setlTime != null) info.put("setl_time", setlTime);
        return info;
    }

    private String success(String msgid, ObjectNode output, String now) {
        ObjectNode body = NODES.objectNode();
        body.put("infcode", 0);
        body.put("inf_refmsgid", msgid == null ? "" : msgid);
        body.put("refmsg_time", now);
        body.put("respond_time", now);
        body.put("err_msg", "");
        body.set("output", output);
        return mapper.writeValueAsString(body);
    }

    private String reject(String msgid, String errMsg, String now) {
        ObjectNode body = NODES.objectNode();
        body.put("infcode", -1);
        body.put("inf_refmsgid", msgid == null ? "" : msgid);
        body.put("refmsg_time", now);
        body.put("respond_time", now);
        body.put("err_msg", errMsg == null ? "" : errMsg);
        return mapper.writeValueAsString(body);
    }

    // ==================== 小工具 ====================

    private void rejectIfSuspended(ChsMockState.PersonSession person) {
        if (person != null && person.behavior() == ScenarioCatalog.Behavior.SUSPENDED) {
            throw new MockRejection("参保状态暂停(psn_insu_stas=2)，不能办理该业务");
        }
    }

    private String unregistered(String mdtrtId) {
        return state.isRegistrationCancelled(mdtrtId) ? "就诊登记已撤销(2202)" : "就诊未登记(2201)";
    }

    private String level(String medListCode, ChsMockConfig config) {
        if (medListCode == null || medListCode.isEmpty()) return "03";
        String prefix = String.valueOf(Character.toUpperCase(medListCode.charAt(0)));
        return config.itemLevelPrefixes().getOrDefault(prefix, "03");
    }

    private static ObjectNode input(JsonNode root) {
        JsonNode in = root.get("input");
        return in != null && in.isObject() ? (ObjectNode) in : NODES.objectNode();
    }

    private static ObjectNode object(JsonNode node) {
        return node != null && node.isObject() ? (ObjectNode) node : null;
    }

    private static ObjectNode requiredObject(JsonNode node, String name) {
        ObjectNode value = object(node);
        if (value == null) throw new MockRejection("缺少 " + name);
        return value;
    }

    private static String required(ObjectNode node, String field) {
        String value = JsonNodes.text(node, field);
        if (value == null) throw new MockRejection("缺少 " + field);
        return value;
    }

    private static BigDecimal requiredDecimal(ObjectNode node, String field) {
        BigDecimal value = JsonNodes.decimal(node, field);
        if (value == null) throw new MockRejection("缺少或非法的金额字段 " + field);
        return value;
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String now() {
        return LocalDateTime.now().format(TIME);
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MockRejection("Mock 延迟被中断");
        }
    }
}
