package com.rhn.mock.protocol.fiscal;

import com.rhn.mock.protocol.fiscal.config.FiscalMockConfig;
import com.rhn.mock.protocol.fiscal.config.FiscalMockConfigService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * 异步结果回调：把票据当前状态按 RHN {@code FiscalReceiptResultDirectory.VerifiedReceiptResult} 的字段推送到回调地址，
 * 报文带 HMAC-SHA256 签名（头 {@code X-Fiscal-Timestamp / X-Fiscal-Signature}），RHN 侧回调入口验签后再调用领域端口。
 */
@Component
public class FiscalCallbackService {
    public static final String AUTHORITY_CODE = "FISCAL";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public enum Mode { NORMAL, DUPLICATE, BAD_SIGNATURE }

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final FiscalMockConfigService configs;
    private final FiscalMockState state;

    public FiscalCallbackService(FiscalMockConfigService configs, FiscalMockState state) {
        this.configs = configs;
        this.state = state;
    }

    /** 票据当前状态对应的回调操作阶段。 */
    public static String operationOf(FiscalReceipt receipt) {
        if (receipt.red()) return "RED_FLUSH";
        return receipt.status() == FiscalReceipt.Status.VOIDED ? "VOID" : "ISSUE";
    }

    public static String outcomeOf(FiscalReceipt receipt) {
        return switch (receipt.status()) {
            case PROCESSING -> "PENDING";
            case FAILED -> "FAILED";
            case VOIDED -> "VOIDED";
            case ISSUED -> receipt.red() ? "RED_FLUSHED" : "ISSUED";
        };
    }

    /** 稳定的消息业务号：同一票据同一状态重复推送得到同一个号，用于测试重复回调。 */
    public static String messageId(FiscalReceipt receipt) {
        return "FMSG-" + receipt.externalReceiptNo() + "-" + operationOf(receipt) + "-" + outcomeOf(receipt);
    }

    public ObjectNode body(FiscalReceipt receipt) {
        String operation = operationOf(receipt);
        boolean voidOp = "VOID".equals(operation);
        boolean success = receipt.status() == FiscalReceipt.Status.ISSUED || voidOp;
        ObjectNode node = mapper.createObjectNode();
        node.put("fiscalAuthorityCode", AUTHORITY_CODE);
        node.put("externalMessageBusinessId", messageId(receipt));
        node.put("commandCode", voidOp ? receipt.voidIdempotencyKey : receipt.idempotencyKey());
        node.put("receiptRequestNo", receipt.receiptRequestNo());
        node.put("operation", operation);
        node.put("outcome", outcomeOf(receipt));
        node.put("externalReceiptNo", receipt.externalReceiptNo());
        if (success && !voidOp) {
            node.put("fiscalCode", receipt.fiscalCode());
            node.put("fiscalNumber", receipt.fiscalNumber());
            node.put("verificationCode", receipt.verificationCode());
            node.put("verifyUrl", receipt.verifyUrl());
        }
        if (success) {
            node.put("issuedAt", (voidOp ? receipt.voidedAt() : receipt.issuedAt()).toString());
        }
        if (voidOp && receipt.voidReason != null) node.put("actionReason", receipt.voidReason);
        if (!success) {
            node.put("errorCode", receipt.errorCode() == null ? "FISCAL_PROCESSING" : receipt.errorCode());
            node.put("errorMessage", receipt.errorMessage() == null ? "票据处理中" : receipt.errorMessage());
        }
        return node;
    }

    /** 推送并记录；未配置回调地址返回空。DUPLICATE 同一报文推两次，BAD_SIGNATURE 用错误密钥签名。 */
    public Optional<FiscalMockState.CallbackDelivery> push(FiscalReceipt receipt, Mode mode, String urlOverride) {
        FiscalMockConfig config = configs.current();
        String url = urlOverride == null || urlOverride.isBlank() ? config.callbackUrl() : urlOverride;
        if (url == null || url.isBlank()) return Optional.empty();
        String payload = mapper.writeValueAsString(body(receipt));
        String secret = mode == Mode.BAD_SIGNATURE ? config.callbackSecret() + "-tampered" : config.callbackSecret();
        FiscalMockState.CallbackDelivery delivery = send(receipt, mode, url, payload, secret);
        if (mode == Mode.DUPLICATE) delivery = send(receipt, mode, url, payload, secret);
        return Optional.of(delivery);
    }

    public static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private FiscalMockState.CallbackDelivery send(FiscalReceipt receipt, Mode mode, String url, String payload, String secret) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        int status = 0;
        String error = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header("X-Fiscal-Timestamp", timestamp)
                    .header("X-Fiscal-Signature", sign(secret, timestamp, payload))
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (java.io.IOException | RuntimeException exception) {
            error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        }
        FiscalMockState.CallbackDelivery delivery = new FiscalMockState.CallbackDelivery(
                LocalDateTime.now().format(TIME), messageId(receipt), receipt.externalReceiptNo(), operationOf(receipt),
                mode.name().toLowerCase(Locale.ROOT), url, status, error, payload);
        state.addDelivery(delivery);
        return delivery;
    }
}
