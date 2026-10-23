package com.rhn.mock.protocol.fiscal;

import com.rhn.mock.core.ExchangeLog;
import com.rhn.mock.core.JsonNodes;
import com.rhn.mock.core.MockHttpResponse;
import com.rhn.mock.protocol.fiscal.config.FiscalMockConfig;
import com.rhn.mock.protocol.fiscal.config.FiscalMockConfigService;
import com.rhn.mock.protocol.fiscal.config.FiscalMockProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 财政电子票据 Mock 引擎：{@code issue / query / void / redFlush} 四个操作。
 * 业务成功与业务拒绝都是 HTTP 200（响应体 success=true/false，字段与 RHN FiscalModels 响应对齐）；
 * 凭据错 401、请求体非 JSON 400；故障注入走 HTTP 层。
 */
@Component
public class FiscalMockEngine {
    public static final List<String> OPERATIONS = List.of("issue", "query", "void", "redFlush");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String JSON = "application/json;charset=UTF-8";

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final FiscalMockProperties properties;
    private final FiscalMockConfigService configs;
    private final FiscalMockState state;
    private final FiscalCallbackService callbacks;
    private final ExchangeLog log;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fiscal-mock-callback");
        thread.setDaemon(true);
        return thread;
    });

    public FiscalMockEngine(FiscalMockProperties properties, FiscalMockConfigService configs, FiscalMockState state,
                            FiscalCallbackService callbacks, ExchangeLog log) {
        this.properties = properties;
        this.configs = configs;
        this.state = state;
        this.callbacks = callbacks;
        this.log = log;
    }

    private static final class Reject extends RuntimeException {
        private final String code;

        Reject(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    public MockHttpResponse handle(String op, String rawBody, String clientId, String clientSecret) {
        FiscalMockConfig config = configs.current();
        if (!config.clientSecret().isBlank()
                && !(properties.clientId().equals(clientId) && config.clientSecret().equals(clientSecret))) {
            return MockHttpResponse.raw(401, "{\"error\":\"X-Client-Id / X-Client-Secret 缺失或不匹配\"}", JSON);
        }
        if (!OPERATIONS.contains(op)) {
            return MockHttpResponse.raw(404, "{\"error\":\"不支持的操作 " + op + "\"}", JSON);
        }
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (RuntimeException failure) {
            return MockHttpResponse.raw(400, "{\"error\":\"请求体不是合法 JSON\"}", JSON);
        }
        if (root == null || !root.isObject()) return MockHttpResponse.raw(400, "{\"error\":\"请求体必须是 JSON 对象\"}", JSON);

        String ref = refOf(root);
        MockHttpResponse respond;
        try {
            respond = dispatch(op, root, config);
        } catch (Reject rejected) {
            respond = MockHttpResponse.json(write(failure(op, rejected.code(), rejected.getMessage())));
        }
        log.add(new ExchangeLog.Entry(now(), FiscalProtocol.ID, op, ref, respond.httpStatus(), rawBody, respond.body()));
        return respond;
    }

    private MockHttpResponse dispatch(String op, JsonNode root, FiscalMockConfig config) {
        String scenario = "issue".equals(op) ? payerScenario(root) : null;
        String fault = scenario != null && Tokens.FAULT_TOKENS.contains(scenario) ? scenario : null;
        if (fault == null && !"NONE".equals(config.fault())
                && (config.faultOp().isBlank() || config.faultOp().equalsIgnoreCase(op))) {
            fault = config.fault();
        }
        if (config.forceDelayMs() > 0) sleep(config.forceDelayMs());
        if ("HTTP500".equals(fault)) return MockHttpResponse.raw(500, "{\"error\":\"simulated fiscal http 500\"}", JSON);
        if ("BADJSON".equals(fault)) return MockHttpResponse.raw(200, "<html>not a json response</html>", "text/plain;charset=UTF-8");
        if ("SLOW".equals(fault)) sleep(config.slowDelayMs());
        if ("TIMEOUT".equals(fault)) sleep(config.timeoutDelayMs());
        if (config.forceErrorCode() != null) {
            throw new Reject(config.forceErrorCode(), config.forceMessage() == null ? "强制应答" : config.forceMessage());
        }
        synchronized (state) {
            ObjectNode out = switch (op) {
                case "issue" -> issue(root, scenario, config);
                case "query" -> query(root, config);
                case "void" -> voidReceipt(root);
                default -> redFlush(root);
            };
            return MockHttpResponse.json(write(out));
        }
    }

    // ==================== issue ====================

    private ObjectNode issue(JsonNode root, String payerScenario, FiscalMockConfig config) {
        String requestNo = required(root, "receiptRequestNo");
        String key = required(root, "idempotencyKey");
        BigDecimal total = amount(root, "totalAmount").setScale(2, java.math.RoundingMode.HALF_UP);
        if (total.signum() <= 0) throw new Reject("FISCAL_INVALID_ARGUMENT", "totalAmount 必须大于 0");

        FiscalReceipt existing = state.byIssueKey(key);
        if (existing == null) existing = state.byRequestNo(requestNo);
        if (existing != null && !existing.red()) {
            if (!existing.receiptRequestNo().equals(requestNo) || !existing.idempotencyKey().equals(key)
                    || existing.amount().compareTo(total) != 0) {
                throw new Reject("FISCAL_IDEMPOTENCY_CONFLICT", "同一幂等键 / 申请号对应的请求内容不一致");
            }
            return issueView(existing, false);
        }
        validateAmounts(root, total);

        String scenario = payerScenario != null && Tokens.ISSUE_TOKENS.contains(payerScenario) ? payerScenario : config.issueScenario();
        if ("FAIL".equals(scenario)) throw new Reject("FISCAL_ISSUE_REJECTED", "财政平台拒绝开具（模拟）");

        String number = String.format("%010d", state.nextNumber());
        String code = fiscalCode(JsonNodes.text(root, "fiscalAuthorityCode"));
        String verification = String.format("%06d", ThreadLocalRandom.current().nextInt(100000, 999999));
        FiscalReceipt receipt = new FiscalReceipt("EINV-" + String.format("%08d", state.nextId()), false, requestNo, key,
                JsonNodes.text(root, "payerName"), total, code, number, verification,
                verifyUrl(config, code, number, verification), null,
                "PENDING".equals(scenario) ? FiscalReceipt.Status.PROCESSING : FiscalReceipt.Status.ISSUED);
        if (receipt.status() == FiscalReceipt.Status.ISSUED) receipt.issuedAt = Instant.now();
        state.put(receipt, true);
        if (receipt.status() == FiscalReceipt.Status.PROCESSING && config.autoCallback()) scheduleCompletion(receipt, config);
        return issueView(receipt, false);
    }

    private void validateAmounts(JsonNode root, BigDecimal total) {
        JsonNode items = root.get("items");
        if (items != null && items.isArray() && !items.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (JsonNode item : items) {
                BigDecimal amount = JsonNodes.decimal(item, "amount");
                if (amount == null) throw new Reject("FISCAL_INVALID_ARGUMENT", "items[].amount 缺失或非法");
                sum = sum.add(amount);
            }
            BigDecimal rounding = JsonNodes.decimal(root, "roundingAmount");
            if (sum.add(rounding == null ? BigDecimal.ZERO : rounding).compareTo(total) != 0) {
                throw new Reject("FISCAL_AMOUNT_MISMATCH", "明细净额合计加舍入差额不等于票面金额");
            }
        }
        BigDecimal rounding = JsonNodes.decimal(root, "roundingAmount");
        String[] funds = {"insuranceAmount", "personalAccountAmount", "patientAmount", "otherFundAmount"};
        BigDecimal fundSum = BigDecimal.ZERO;
        boolean any = false;
        for (String field : funds) {
            BigDecimal value = JsonNodes.decimal(root, field);
            if (value == null) continue;
            if (value.signum() < 0) throw new Reject("FISCAL_AMOUNT_MISMATCH", field + " 不能为负");
            fundSum = fundSum.add(value);
            any = true;
        }
        if (any && fundSum.compareTo(total) != 0
                && (rounding == null || fundSum.add(rounding).compareTo(total) != 0)) {
            throw new Reject("FISCAL_AMOUNT_MISMATCH", "医保统筹 / 个人账户 / 患者实付 / 其他基金之和与票面金额不一致");
        }
    }

    // ==================== query ====================

    private ObjectNode query(JsonNode root, FiscalMockConfig config) {
        String requestNo = JsonNodes.text(root, "receiptRequestNo");
        String externalNo = JsonNodes.text(root, "externalReceiptNo");
        if (requestNo == null && externalNo == null) throw new Reject("FISCAL_INVALID_ARGUMENT", "receiptRequestNo 与 externalReceiptNo 至少给一个");
        FiscalReceipt receipt = state.find(requestNo, externalNo);
        if (receipt == null) throw new Reject("FISCAL_RECEIPT_NOT_FOUND", "未找到票据");
        if (receipt.status() == FiscalReceipt.Status.PROCESSING) {
            if (receipt.queryCount >= Math.max(0, config.pendingQueries())) {
                complete(receipt, true);
            } else {
                receipt.queryCount++;
            }
        }
        return issueView(receipt, true);
    }

    // ==================== void ====================

    private ObjectNode voidReceipt(JsonNode root) {
        String key = required(root, "idempotencyKey");
        String externalNo = required(root, "externalReceiptNo");
        FiscalReceipt receipt = state.byExternalNo(externalNo);
        if (receipt == null) throw new Reject("FISCAL_RECEIPT_NOT_FOUND", "未找到票据");
        if (receipt.red()) throw new Reject("FISCAL_VOID_NOT_ALLOWED", "红字票据不可作废");
        if (receipt.status() == FiscalReceipt.Status.VOIDED) {
            if (key.equals(receipt.voidIdempotencyKey)) return voidView(receipt);
            throw new Reject("FISCAL_ALREADY_VOIDED", "票据已作废，不能重复作废");
        }
        if (receipt.status() != FiscalReceipt.Status.ISSUED) throw new Reject("FISCAL_NOT_ISSUED", "票据尚未开具成功，不能作废");
        if (receipt.redExternalReceiptNo() != null) throw new Reject("FISCAL_ALREADY_RED_FLUSHED", "票据已冲红，不能作废");
        receipt.status = FiscalReceipt.Status.VOIDED;
        receipt.voidedAt = Instant.now();
        receipt.voidIdempotencyKey = key;
        receipt.voidReason = JsonNodes.text(root, "reason");
        return voidView(receipt);
    }

    // ==================== redFlush ====================

    private ObjectNode redFlush(JsonNode root) {
        String key = required(root, "idempotencyKey");
        String originalNo = required(root, "originalExternalReceiptNo");
        FiscalReceipt replay = state.byRedKey(key);
        if (replay != null) {
            if (originalNo.equals(replay.originalExternalReceiptNo())) return redView(replay);
            throw new Reject("FISCAL_IDEMPOTENCY_CONFLICT", "同一幂等键对应的冲红对象不一致");
        }
        FiscalReceipt original = state.byExternalNo(originalNo);
        if (original == null) throw new Reject("FISCAL_RECEIPT_NOT_FOUND", "未找到原票据");
        if (original.red()) throw new Reject("FISCAL_RED_FLUSH_NOT_ALLOWED", "红字票据不能再冲红");
        if (original.status() == FiscalReceipt.Status.VOIDED) throw new Reject("FISCAL_RECEIPT_VOIDED", "原票据已作废，不能冲红");
        if (original.status() != FiscalReceipt.Status.ISSUED) throw new Reject("FISCAL_NOT_ISSUED", "原票据尚未开具成功，不能冲红");
        String code = JsonNodes.text(root, "originalFiscalCode");
        String number = JsonNodes.text(root, "originalFiscalNumber");
        if ((code != null && !code.equals(original.fiscalCode())) || (number != null && !number.equals(original.fiscalNumber()))) {
            throw new Reject("FISCAL_RECEIPT_MISMATCH", "原票据代码 / 号码与外部票据号不一致");
        }
        // RHN 冲红请求不带金额，按原额全额冲红；已冲红即再冲会超出原额。
        if (original.redExternalReceiptNo() != null) throw new Reject("FISCAL_ALREADY_RED_FLUSHED", "原票据已全额冲红，再冲红将超过原额");

        FiscalMockConfig config = configs.current();
        String redNumber = String.format("%010d", state.nextNumber());
        String verification = String.format("%06d", ThreadLocalRandom.current().nextInt(100000, 999999));
        FiscalReceipt red = new FiscalReceipt("RED-EINV-" + String.format("%08d", state.nextId()), true,
                JsonNodes.text(root, "receiptRequestNo"), key, original.payerName(), original.amount().negate(),
                original.fiscalCode(), redNumber, verification, verifyUrl(config, original.fiscalCode(), redNumber, verification),
                originalNo, FiscalReceipt.Status.ISSUED);
        red.issuedAt = Instant.now();
        original.redExternalReceiptNo = red.externalReceiptNo();
        state.put(red, true);
        return redView(red);
    }

    // ==================== 异步完成 ====================

    /** 处理中 → 成功 / 失败（查询次数到达、管理端点手动触发、自动回调共用）。 */
    public FiscalReceipt complete(FiscalReceipt receipt, boolean success) {
        synchronized (state) {
            if (receipt.status() != FiscalReceipt.Status.PROCESSING) return receipt;
            if (success) {
                receipt.status = FiscalReceipt.Status.ISSUED;
                receipt.issuedAt = Instant.now();
            } else {
                receipt.status = FiscalReceipt.Status.FAILED;
                receipt.errorCode = "FISCAL_ISSUE_REJECTED";
                receipt.errorMessage = "财政平台处理失败（模拟）";
            }
            return receipt;
        }
    }

    private void scheduleCompletion(FiscalReceipt receipt, FiscalMockConfig config) {
        scheduler.schedule(() -> {
            try {
                complete(receipt, true);
                callbacks.push(receipt, FiscalCallbackService.Mode.NORMAL, null);
            } catch (RuntimeException ignored) {
                // 后台线程不抛出；失败见 /mock/fiscal/callbacks
            }
        }, Math.max(0, config.callbackDelayMs()), TimeUnit.MILLISECONDS);
    }

    // ==================== 响应构造（字段与 FiscalModels 响应对齐） ====================

    /** FiscalIssueResponse：success / outcome / externalReceiptNo / fiscalCode / fiscalNumber / verificationCode / verifyUrl / issuedAt / errorCode / errorMessage。 */
    private ObjectNode issueView(FiscalReceipt receipt, boolean forQuery) {
        ObjectNode node = mapper.createObjectNode();
        boolean ok = receipt.status() == FiscalReceipt.Status.ISSUED || receipt.status() == FiscalReceipt.Status.VOIDED;
        String outcome = switch (receipt.status()) {
            case PROCESSING -> "PENDING";
            case FAILED -> "FAILED";
            case VOIDED -> forQuery ? "VOIDED" : "ISSUED";
            case ISSUED -> forQuery && receipt.red() ? "RED_FLUSHED" : "ISSUED";
        };
        node.put("success", ok);
        node.put("outcome", outcome);
        node.put("externalReceiptNo", receipt.externalReceiptNo());
        if (ok) {
            node.put("fiscalCode", receipt.fiscalCode());
            node.put("fiscalNumber", receipt.fiscalNumber());
            node.put("verificationCode", receipt.verificationCode());
            node.put("verifyUrl", receipt.verifyUrl());
            node.put("issuedAt", receipt.issuedAt().toString());
            node.putNull("errorCode");
            node.putNull("errorMessage");
        } else {
            for (String field : List.of("fiscalCode", "fiscalNumber", "verificationCode", "verifyUrl", "issuedAt")) node.putNull(field);
            boolean pending = receipt.status() == FiscalReceipt.Status.PROCESSING;
            node.put("errorCode", pending ? "FISCAL_PROCESSING" : receipt.errorCode());
            node.put("errorMessage", pending ? "财政平台处理中，请稍后查询" : receipt.errorMessage());
        }
        return node;
    }

    /** FiscalVoidResponse：success / externalReceiptNo / voidedAt / errorCode / errorMessage。 */
    private ObjectNode voidView(FiscalReceipt receipt) {
        ObjectNode node = mapper.createObjectNode();
        node.put("success", true);
        node.put("externalReceiptNo", receipt.externalReceiptNo());
        node.put("voidedAt", receipt.voidedAt().toString());
        node.putNull("errorCode");
        node.putNull("errorMessage");
        return node;
    }

    /** FiscalRedFlushResponse：success / redExternalReceiptNo / redFiscalCode / redFiscalNumber / verificationCode / verifyUrl / issuedAt / errorCode / errorMessage。 */
    private ObjectNode redView(FiscalReceipt red) {
        ObjectNode node = mapper.createObjectNode();
        node.put("success", true);
        node.put("redExternalReceiptNo", red.externalReceiptNo());
        node.put("redFiscalCode", red.fiscalCode());
        node.put("redFiscalNumber", red.fiscalNumber());
        node.put("verificationCode", red.verificationCode());
        node.put("verifyUrl", red.verifyUrl());
        node.put("issuedAt", red.issuedAt().toString());
        node.putNull("errorCode");
        node.putNull("errorMessage");
        return node;
    }

    private ObjectNode failure(String op, String code, String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("success", false);
        switch (op) {
            case "issue", "query" -> {
                node.put("outcome", "FAILED");
                for (String field : List.of("externalReceiptNo", "fiscalCode", "fiscalNumber", "verificationCode", "verifyUrl", "issuedAt")) node.putNull(field);
            }
            case "void" -> {
                node.putNull("externalReceiptNo");
                node.putNull("voidedAt");
            }
            default -> {
                for (String field : List.of("redExternalReceiptNo", "redFiscalCode", "redFiscalNumber", "verificationCode", "verifyUrl", "issuedAt")) node.putNull(field);
            }
        }
        node.put("errorCode", code);
        node.put("errorMessage", message);
        return node;
    }

    // ==================== 小工具 ====================

    /** 票据代码：行政区划 4 位 + 0601 + 年份后两位，与 RHN 进程内 Mock 一致。 */
    static String fiscalCode(String authority) {
        String area = "3601";
        if (authority != null && authority.matches("^\\d{4,}.*")) area = authority.substring(0, 4);
        else if (authority != null && authority.matches("^\\d{2}.*")) area = authority.substring(0, 2) + "01";
        return String.format("%s0601%02d", area, Year.now().getValue() % 100);
    }

    private static String verifyUrl(FiscalMockConfig config, String code, String number, String verification) {
        return config.verifyBaseUrl() + "?bill_code=" + code + "&bill_no=" + number + "&check_code=" + verification;
    }

    private static String payerScenario(JsonNode root) {
        String name = JsonNodes.text(root, "payerName");
        if (name == null) return null;
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("TEST-") ? upper.substring(5) : null;
    }

    private static String refOf(JsonNode root) {
        String ref = JsonNodes.text(root, "receiptRequestNo");
        if (ref == null) ref = JsonNodes.text(root, "externalReceiptNo");
        if (ref == null) ref = JsonNodes.text(root, "originalExternalReceiptNo");
        return ref;
    }

    private static String required(JsonNode node, String field) {
        String value = JsonNodes.text(node, field);
        if (value == null) throw new Reject("FISCAL_INVALID_ARGUMENT", "缺少 " + field);
        return value;
    }

    private static BigDecimal amount(JsonNode node, String field) {
        BigDecimal value = JsonNodes.decimal(node, field);
        if (value == null) throw new Reject("FISCAL_INVALID_ARGUMENT", "缺少或非法的金额字段 " + field);
        return value;
    }

    private String write(ObjectNode node) {
        return mapper.writeValueAsString(node);
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
        }
    }

    /** payerName 的 TEST-* 令牌分组。 */
    private static final class Tokens {
        static final java.util.Set<String> FAULT_TOKENS = java.util.Set.of("HTTP500", "BADJSON", "SLOW", "TIMEOUT");
        static final java.util.Set<String> ISSUE_TOKENS = java.util.Set.of("PENDING", "FAIL");
    }
}
