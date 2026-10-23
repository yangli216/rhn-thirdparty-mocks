package com.rhn.mock.protocol.ca;

import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** CA Mock 内存态：签名人、签名任务、登录请求、证书登录挑战、自动签授权、UKey 签名流水，重启即清空。 */
@Component
public class CaMockState {
    public enum SignerState { PENDING_REVIEW, REJECTED, CERTIFICATE_PENDING, READY, DISABLED }

    public enum OrderState { AWAITING_SIGNER, SIGNED, REJECTED, EXPIRED, CANCELLED, VOIDED_BY_SIGNER, FAILED }

    public enum LoginState { PENDING, CONFIRMED, REJECTED, EXPIRED }

    public enum GrantState { NONE, PENDING, GRANTED, REFUSED }

    public record Signer(String externalSignerId, Long userId, Long practitionerId, String name, String mobileTail,
                         CaScenarioCatalog.Behavior behavior, SignerState state, KeyPair keys,
                         X509Certificate certificate, Instant revokedAt, String revokeReason, Instant createdAt) {
        public Signer with(SignerState next, KeyPair nextKeys, X509Certificate nextCertificate) {
            return new Signer(externalSignerId, userId, practitionerId, name, mobileTail, behavior, next, nextKeys,
                    nextCertificate, revokedAt, revokeReason, createdAt);
        }

        public Signer revoked(Instant at, String reason) {
            return new Signer(externalSignerId, userId, practitionerId, name, mobileTail, behavior, state, keys,
                    certificate, at, reason, createdAt);
        }

        public boolean isRevoked() {
            return revokedAt != null;
        }
    }

    public record Order(String externalOrderId, Long signingOrderId, Long tenantId, Long organizationId,
                        String requestedSignerId, String signerId, String mode, String sceneCode,
                        String digestAlgorithm, String digest, String subject, String patientDisplay,
                        Instant createdAt, Instant expiresAt, OrderState state, String message, String errorCode,
                        String envelope, String signatureValue, String certificate, Instant signedAt,
                        String timestampAuthority, String timestampToken) {
        public Order withState(OrderState next, String nextMessage, String nextErrorCode) {
            return new Order(externalOrderId, signingOrderId, tenantId, organizationId, requestedSignerId, signerId,
                    mode, sceneCode, digestAlgorithm, digest, subject, patientDisplay, createdAt, expiresAt, next,
                    nextMessage, nextErrorCode, envelope, signatureValue, certificate, signedAt, timestampAuthority,
                    timestampToken);
        }

        public Order signed(String nextSignerId, String nextEnvelope, String nextSignature, String nextCertificate,
                            Instant at, String tsa, String tsaToken) {
            return new Order(externalOrderId, signingOrderId, tenantId, organizationId, requestedSignerId,
                    nextSignerId, mode, sceneCode, digestAlgorithm, digest, subject, patientDisplay, createdAt,
                    expiresAt, OrderState.SIGNED, null, null, nextEnvelope, nextSignature, nextCertificate, at, tsa,
                    tsaToken);
        }
    }

    public record Login(String externalRequestId, LoginState state, String signerId, Instant expiresAt,
                        boolean autoSignGranted, String message) {
        public Login with(LoginState next, String nextSigner, String nextMessage) {
            return new Login(externalRequestId, next, nextSigner, expiresAt, autoSignGranted, nextMessage);
        }
    }

    /** 证书登录挑战：{@code consumedAt} 非空即已用过，再次提交按重放处理。 */
    public record Challenge(String challengeId, String toBeSigned, Instant expiresAt, Instant consumedAt) {
    }

    public record Grant(GrantState state, long durationSeconds, Instant until, Instant requestExpiresAt) {
    }

    /** 模拟 UKey 中间件的一次本地签名。 */
    public record ClientSign(String time, String externalSignerId, String dataDigest, String signatureValue) {
    }

    private final ConcurrentMap<String, Signer> signers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Order> orders = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> orderBySigningOrder = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Login> logins = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Grant> grants = new ConcurrentHashMap<>();
    private final List<ClientSign> clientSigns = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    public String nextId(String prefix) {
        return prefix + "-" + String.format("%06d", sequence.incrementAndGet());
    }

    // ---- signers ----
    public ConcurrentMap<String, Signer> signers() {
        return signers;
    }

    public Signer signer(String id) {
        return id == null ? null : signers.get(id);
    }

    public Signer signerByFingerprint(String fingerprint) {
        for (Signer signer : signers.values()) {
            if (signer.certificate() != null && fingerprint.equals(CaCrypto.fingerprint(signer.certificate()))) return signer;
        }
        return null;
    }

    // ---- orders ----
    public ConcurrentMap<String, Order> orders() {
        return orders;
    }

    public ConcurrentMap<Long, String> orderBySigningOrder() {
        return orderBySigningOrder;
    }

    public Order order(String externalOrderId) {
        return externalOrderId == null ? null : orders.get(externalOrderId);
    }

    public Collection<Order> allOrders() {
        List<Order> all = new ArrayList<>(orders.values());
        all.sort(java.util.Comparator.comparing(Order::createdAt).thenComparing(Order::externalOrderId));
        return all;
    }

    // ---- logins / challenges / grants ----
    public ConcurrentMap<String, Login> logins() {
        return logins;
    }

    public ConcurrentMap<String, Challenge> challenges() {
        return challenges;
    }

    public ConcurrentMap<String, Grant> grants() {
        return grants;
    }

    public List<ClientSign> clientSigns() {
        return clientSigns;
    }

    public void reset() {
        signers.clear();
        orders.clear();
        orderBySigningOrder.clear();
        logins.clear();
        challenges.clear();
        grants.clear();
        clientSigns.clear();
        sequence.set(0);
    }
}
