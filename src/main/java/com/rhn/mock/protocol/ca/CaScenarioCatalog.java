package com.rhn.mock.protocol.ca;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 备案（{@code signers/enroll}）时按 {@code identityNumber} 选择的测试人员与故障场景，
 * 与医保 Mock 按证件号选场景同一套路；管理端签发测试人员时也可直接指定。
 * 未匹配的证件号一律是 NORMAL，保证向导可自由输入。
 */
@Component
public class CaScenarioCatalog {
    public enum Behavior {
        /** 备案即 READY，证书有效，签名 / 登录正常。 */
        NORMAL,
        /** 备案后停在 PENDING_REVIEW，管理端 approve 后才有证书。 */
        PENDING_REVIEW,
        /** 备案被拒（REJECTED），无证书。 */
        REJECTED,
        /** 审核通过但证书未下发（CERTIFICATE_PENDING），管理端 approve 后 READY。 */
        CERT_PENDING,
        /** 账号已停用（DISABLED），无证书。 */
        DISABLED,
        /** READY，但证书已过期（签名时仍会签，RHN 本地验签应判过期）。 */
        CERT_EXPIRED,
        /** READY 后证书已吊销：厂商侧拒绝签名 / 登录，证书状态为 REVOKED。 */
        REVOKED,
        /** 签名任务进入终态 FAILED（厂商侧签名失败）。 */
        SIGN_FAIL,
        /** 签名人在手机端拒签（任务 REJECTED）。 */
        SIGN_REJECT,
        /** 返回的签名值被破坏（RHN 本地验签应判 CA_SIGNATURE_INVALID）。 */
        BAD_SIGNATURE,
        /** 封装内嵌摘要与请求摘要不一致（RHN envelopeBinds 应失败）。 */
        BAD_ENVELOPE,
        /** 厂商验签接口对该人员的证书一律判不通过。 */
        VERIFY_FAIL,
        /** 涉及该人员的调用慢响应（slowDelayMs）。 */
        SLOW,
        /** 涉及该人员的调用超过客户端超时（timeoutDelayMs）。 */
        TIMEOUT,
        /** 涉及该人员的调用返回 HTTP 500。 */
        HTTP500,
        /** 涉及该人员的调用返回非 JSON 正文。 */
        BADJSON
    }

    public Behavior behaviorFor(String identityNumber) {
        String key = identityNumber == null ? "" : identityNumber.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "TEST-REVIEW" -> Behavior.PENDING_REVIEW;
            case "TEST-REJECT" -> Behavior.REJECTED;
            case "TEST-CERT-PENDING" -> Behavior.CERT_PENDING;
            case "TEST-DISABLED" -> Behavior.DISABLED;
            case "TEST-CERT-EXPIRED" -> Behavior.CERT_EXPIRED;
            case "TEST-REVOKED" -> Behavior.REVOKED;
            case "TEST-SIGN-FAIL" -> Behavior.SIGN_FAIL;
            case "TEST-SIGN-REJECT" -> Behavior.SIGN_REJECT;
            case "TEST-BAD-SIGNATURE" -> Behavior.BAD_SIGNATURE;
            case "TEST-BAD-ENVELOPE" -> Behavior.BAD_ENVELOPE;
            case "TEST-VERIFY-FAIL" -> Behavior.VERIFY_FAIL;
            case "TEST-SLOW" -> Behavior.SLOW;
            case "TEST-TIMEOUT" -> Behavior.TIMEOUT;
            case "TEST-HTTP500" -> Behavior.HTTP500;
            case "TEST-BADJSON" -> Behavior.BADJSON;
            default -> Behavior.NORMAL;
        };
    }

    public Behavior parse(String name) {
        if (name == null || name.isBlank()) return Behavior.NORMAL;
        try {
            return Behavior.valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知场景: " + name);
        }
    }
}
