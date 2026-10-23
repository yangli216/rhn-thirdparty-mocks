package com.rhn.mock.protocol.ca;

import com.rhn.mock.core.JsonNodes;
import com.rhn.mock.protocol.ca.CaMockState.Grant;
import com.rhn.mock.protocol.ca.CaMockState.GrantState;
import com.rhn.mock.protocol.ca.CaMockState.Login;
import com.rhn.mock.protocol.ca.CaMockState.LoginState;
import com.rhn.mock.protocol.ca.CaMockState.Order;
import com.rhn.mock.protocol.ca.CaMockState.OrderState;
import com.rhn.mock.protocol.ca.CaMockState.Signer;
import com.rhn.mock.protocol.ca.CaMockState.SignerState;
import com.rhn.mock.protocol.ca.CaScenarioCatalog.Behavior;
import com.rhn.mock.protocol.ca.config.CaMockConfigService;
import com.rhn.mock.protocol.ca.config.CaMockProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CA 厂商仿真的业务规则：签名人备案、扫码登录、证书登录挑战、签名任务（提交 / 查询 / 取结果 / 取消）、
 * 自动签授权、时间戳、信任材料与验签。签名人侧动作（手机确认、UKey 本地签名）经管理端调用同名方法模拟。
 */
@Service
public class CaVendorService {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    static final int BATCH_LIMIT = 100;
    private static final Set<String> REMOTE_MODES = Set.of("PUSH", "QR_CLAIM", "AUTO");
    private static final Set<String> KNOWN_MODES = Set.of("PUSH", "QR_CLAIM", "AUTO", "CLIENT_TOKEN");

    private final CaMockState state;
    private final CaAuthority authority;
    private final CaScenarioCatalog scenarios;
    private final CaMockConfigService configs;
    private final CaMockProperties properties;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final SecureRandom random = new SecureRandom();

    public CaVendorService(CaMockState state, CaAuthority authority, CaScenarioCatalog scenarios,
                           CaMockConfigService configs, CaMockProperties properties) {
        this.state = state;
        this.authority = authority;
        this.scenarios = scenarios;
        this.configs = configs;
        this.properties = properties;
    }

    // ==================== 签名人备案 ====================

    public ObjectNode enroll(JsonNode body) {
        String name = required(body, "name");
        String identityNumber = required(body, "identityNumber");
        Long userId = longValue(body, "userId");
        Long practitionerId = longValue(body, "practitionerId");
        String mobile = JsonNodes.text(body, "mobile");
        String id = userId == null ? state.nextId("mock-signer") : "mock-" + userId;
        Signer existing = state.signer(id);
        if (existing != null) return enrollment(existing, true);
        Signer created = newSigner(id, userId, practitionerId, name, mobile, scenarios.behaviorFor(identityNumber));
        Signer previous = state.signers().putIfAbsent(id, created);
        return enrollment(previous == null ? created : previous, true);
    }

    public ObjectNode querySigner(JsonNode body) {
        return enrollment(requireSigner(required(body, "externalSignerId")));
    }

    /** 管理端 / 备案共用：按场景建出签名人（READY 场景现签证书）。 */
    public Signer newSigner(String id, Long userId, Long practitionerId, String name, String mobile, Behavior behavior) {
        String tail = mobile == null || mobile.length() < 4 ? null : mobile.substring(mobile.length() - 4);
        Instant now = Instant.now();
        Signer pending = new Signer(id, userId, practitionerId, name, tail, behavior, SignerState.READY, null, null,
                null, null, now);
        return switch (behavior) {
            case PENDING_REVIEW -> pending.with(SignerState.PENDING_REVIEW, null, null);
            case REJECTED -> pending.with(SignerState.REJECTED, null, null);
            case CERT_PENDING -> pending.with(SignerState.CERTIFICATE_PENDING, null, null);
            case DISABLED -> pending.with(SignerState.DISABLED, null, null);
            case CERT_EXPIRED -> issued(pending, now.truncatedTo(ChronoUnit.SECONDS).minus(60, ChronoUnit.DAYS),
                    now.truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.DAYS));
            case REVOKED -> issued(pending, authority.defaultNotBefore(), authority.defaultNotAfter())
                    .revoked(now, "KEY_COMPROMISE");
            default -> issued(pending, authority.defaultNotBefore(), authority.defaultNotAfter());
        };
    }

    private Signer issued(Signer signer, Instant notBefore, Instant notAfter) {
        CaAuthority.Issued issued = authority.issueSigner(signer.name(), signer.externalSignerId(), notBefore, notAfter);
        return signer.with(SignerState.READY, issued.keys(), issued.certificate());
    }

    /** 审核 / 证书下发通过：PENDING_REVIEW、CERTIFICATE_PENDING → READY 并签发证书。 */
    public Signer approve(String externalSignerId) {
        Signer signer = requireSigner(externalSignerId);
        if (signer.state() == SignerState.READY) return signer;
        if (signer.state() != SignerState.PENDING_REVIEW && signer.state() != SignerState.CERTIFICATE_PENDING) {
            throw new CaFailure("CA_SIGNER_STATE_CONFLICT", "签名人状态 " + signer.state() + " 不能审核通过");
        }
        Signer ready = issued(signer, authority.defaultNotBefore(), authority.defaultNotAfter());
        state.signers().put(externalSignerId, ready);
        return ready;
    }

    public Signer revoke(String externalSignerId, String reason) {
        Signer signer = requireSigner(externalSignerId);
        if (signer.certificate() == null) throw new CaFailure("CA_SIGNER_STATE_CONFLICT", "签名人尚无证书");
        Signer revoked = signer.revoked(Instant.now(), reason == null || reason.isBlank() ? "UNSPECIFIED" : reason);
        state.signers().put(externalSignerId, revoked);
        return revoked;
    }

    private ObjectNode enrollment(Signer signer) {
        return enrollment(signer, false);
    }

    private ObjectNode enrollment(Signer signer, boolean atEnroll) {
        ObjectNode node = NODES.objectNode();
        node.put("externalSignerId", signer.externalSignerId());
        boolean revokedView = signer.isRevoked() && !(atEnroll && signer.behavior() == Behavior.REVOKED);
        // TEST-REVOKED：备案时仍返回 READY 便于 RHN 绑定，之后一切签名 / 登录被拒；query 才体现为 DISABLED
        node.put("state", revokedView ? SignerState.DISABLED.name() : signer.state().name());
        if (signer.certificate() == null) {
            node.putNull("certificate");
            node.putNull("certificateNotAfter");
        } else {
            node.put("certificate", CaCrypto.b64(CaCrypto.encoded(signer.certificate())));
            node.put("certificateNotAfter", signer.certificate().getNotAfter().toInstant().toString());
        }
        node.put("message", switch (signer.state()) {
            case PENDING_REVIEW -> "备案审核中";
            case REJECTED -> "备案被拒绝";
            case CERTIFICATE_PENDING -> "证书尚未下发";
            case DISABLED -> "账号已停用";
            case READY -> signer.isRevoked() ? "证书已吊销：" + signer.revokeReason() : "";
        });
        return node;
    }

    // ==================== 扫码登录 ====================

    public ObjectNode loginStart(JsonNode body) {
        Instant now = Instant.now();
        state.logins().values().removeIf(value -> value.expiresAt().isBefore(now.minusSeconds(600)));
        String id = state.nextId("mock-login");
        Instant expiresAt = now.plusSeconds(configs.current().loginChallengeTtlSeconds()).truncatedTo(ChronoUnit.SECONDS);
        boolean autoSign = body != null && "true".equals(JsonNodes.text(body, "enableAutoSign"));
        state.logins().put(id, new Login(id, LoginState.PENDING, null, expiresAt, autoSign, null));
        ObjectNode node = NODES.objectNode();
        node.put("externalRequestId", id);
        node.put("qrContent", "mock-ca://login/" + id);
        node.put("expiresAt", expiresAt.toString());
        return node;
    }

    public ObjectNode loginPoll(JsonNode body) {
        String id = required(body, "externalRequestId");
        Login login = state.logins().get(id);
        if (login != null && login.state() == LoginState.PENDING) {
            if (login.expiresAt().isBefore(Instant.now())) {
                login = login.with(LoginState.EXPIRED, null, "登录请求已过期");
                state.logins().put(id, login);
            } else if (!configs.current().autoConfirmLoginSigner().isBlank()) {
                login = confirmLogin(id, configs.current().autoConfirmLoginSigner());
            }
        }
        ObjectNode node = NODES.objectNode();
        if (login == null) {
            node.put("state", LoginState.EXPIRED.name());
            node.putNull("externalSignerId");
            node.putNull("certificate");
            node.put("autoSignGranted", false);
            node.put("message", "登录请求不存在或已被领取");
            return node;
        }
        // 终态只能被领取一次，与 RHN 侧「确认结果只能被领取一次」对应
        if (login.state() != LoginState.PENDING) state.logins().remove(id);
        Signer signer = state.signer(login.signerId());
        node.put("state", login.state().name());
        if (login.signerId() == null) node.putNull("externalSignerId");
        else node.put("externalSignerId", login.signerId());
        if (login.state() == LoginState.CONFIRMED && signer != null && signer.certificate() != null) {
            node.put("certificate", CaCrypto.b64(CaCrypto.encoded(signer.certificate())));
        } else {
            node.putNull("certificate");
        }
        node.put("autoSignGranted", login.state() == LoginState.CONFIRMED && login.autoSignGranted());
        if (login.message() != null) node.put("message", login.message());
        return node;
    }

    /** 模拟医生在手机端扫码并确认。已吊销 / 未就绪的签名人按拒绝处理。 */
    public Login confirmLogin(String externalRequestId, String externalSignerId) {
        Login login = state.logins().get(externalRequestId);
        if (login == null || login.state() != LoginState.PENDING || login.expiresAt().isBefore(Instant.now())) {
            throw new CaFailure("CA_LOGIN_NOT_PENDING", "没有待确认的登录请求 " + externalRequestId);
        }
        Signer signer = requireSigner(externalSignerId);
        Login next;
        if (signer.state() != SignerState.READY) {
            next = login.with(LoginState.REJECTED, externalSignerId, "签名人状态 " + signer.state() + " 不能登录");
        } else if (signer.isRevoked()) {
            next = login.with(LoginState.REJECTED, externalSignerId, "证书已吊销");
        } else {
            next = login.with(LoginState.CONFIRMED, externalSignerId, null);
        }
        state.logins().put(externalRequestId, next);
        return next;
    }

    public Login rejectLogin(String externalRequestId) {
        Login login = state.logins().get(externalRequestId);
        if (login == null || login.state() != LoginState.PENDING) {
            throw new CaFailure("CA_LOGIN_NOT_PENDING", "没有待确认的登录请求 " + externalRequestId);
        }
        Login next = login.with(LoginState.REJECTED, null, "已在手机端拒绝登录");
        state.logins().put(externalRequestId, next);
        return next;
    }

    /** 让待确认的登录请求立即过期（挑战过期场景）。 */
    public void expireLogin(String externalRequestId) {
        Login login = state.logins().get(externalRequestId);
        if (login == null) throw new CaFailure("CA_LOGIN_NOT_FOUND", "没有登录请求 " + externalRequestId);
        state.logins().put(externalRequestId, new Login(login.externalRequestId(), login.state(), login.signerId(),
                Instant.now().minusSeconds(1), login.autoSignGranted(), login.message()));
    }

    // ==================== 证书登录挑战 ====================

    public ObjectNode certificateChallenge() {
        String id = state.nextId("mock-chal");
        Instant expiresAt = Instant.now().plusSeconds(configs.current().loginChallengeTtlSeconds())
                .truncatedTo(ChronoUnit.SECONDS);
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String text = "MOCK-CA-LOGIN-1\nchallenge:" + id + "\nnonce:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(nonce) + "\nexpires:" + expiresAt;
        String toBeSigned = CaCrypto.b64(text.getBytes(StandardCharsets.UTF_8));
        state.challenges().put(id, new CaMockState.Challenge(id, toBeSigned, expiresAt, null));
        ObjectNode node = NODES.objectNode();
        node.put("challengeId", id);
        node.put("toBeSigned", toBeSigned);
        node.put("expiresAt", expiresAt.toString());
        return node;
    }

    public void expireChallenge(String challengeId) {
        CaMockState.Challenge challenge = state.challenges().get(challengeId);
        if (challenge == null) throw new CaFailure("CA_CHALLENGE_NOT_FOUND", "没有挑战 " + challengeId);
        state.challenges().put(challengeId, new CaMockState.Challenge(challengeId, challenge.toBeSigned(),
                Instant.now().minusSeconds(1), challenge.consumedAt()));
    }

    /** 证书登录验签：挑战一次性，无论成败都被消耗；结果以 {@code valid} + {@code reasonCode} 表达。 */
    public ObjectNode certificateVerify(JsonNode body) {
        String id = required(body, "challengeId");
        String signatureValue = required(body, "signatureValue");
        String certificate = required(body, "certificate");
        Instant now = Instant.now();
        CaMockState.Challenge[] taken = new CaMockState.Challenge[1];
        CaMockState.Challenge[] previous = new CaMockState.Challenge[1];
        state.challenges().computeIfPresent(id, (key, value) -> {
            previous[0] = value;
            taken[0] = value.consumedAt() == null
                    ? new CaMockState.Challenge(id, value.toBeSigned(), value.expiresAt(), now) : value;
            return taken[0];
        });
        if (previous[0] == null) return verdict(false, "CA_CHALLENGE_NOT_FOUND", "挑战不存在", null);
        if (previous[0].consumedAt() != null) return verdict(false, "CA_CHALLENGE_REPLAYED", "挑战已使用，拒绝重放", null);
        if (previous[0].expiresAt().isBefore(now)) return verdict(false, "CA_CHALLENGE_EXPIRED", "挑战已过期", null);
        X509Certificate presented;
        byte[] signature;
        try {
            presented = CaCrypto.certificate(certificate);
            signature = CaCrypto.fromB64(signatureValue);
        } catch (IllegalArgumentException exception) {
            return verdict(false, "CA_SIGNATURE_MALFORMED", "签名值或证书格式无效", null);
        }
        Signer signer = state.signerByFingerprint(CaCrypto.fingerprint(presented));
        String failure = certificateFailure(presented, now, signer);
        if (failure != null) return verdict(false, failure, message(failure), signer);
        byte[] data = CaCrypto.fromB64(previous[0].toBeSigned());
        if (signer != null && signer.behavior() == Behavior.VERIFY_FAIL
                || !CaCrypto.verify(presented.getPublicKey(), data, signature)) {
            return verdict(false, "CA_SIGNATURE_INVALID", message("CA_SIGNATURE_INVALID"), signer);
        }
        return verdict(true, null, null, signer);
    }

    // ==================== 签名任务 ====================

    public ObjectNode submit(JsonNode body) {
        Long signingOrderId = longValue(body, "signingOrderId");
        if (signingOrderId == null) throw invalid("缺少 signingOrderId");
        String existingId = state.orderBySigningOrder().get(signingOrderId);
        if (existingId != null) return orderStatus(refresh(state.order(existingId)));
        String mode = required(body, "mode").toUpperCase();
        if (!KNOWN_MODES.contains(mode)) throw invalid("未知 mode: " + mode);
        if (!REMOTE_MODES.contains(mode)) throw new CaFailure("CA_MODE_UNSUPPORTED", "CLIENT_TOKEN 在本地完成，不经过远程签名接口");
        String algorithm = required(body, "digestAlgorithm");
        if (!CaCrypto.supportsDigest(algorithm)) throw invalid("不支持的 digestAlgorithm: " + algorithm);
        String digest = required(body, "statementDigest");
        try {
            if (CaCrypto.fromB64(digest).length != CaCrypto.digestLength(algorithm)) throw invalid("statementDigest 长度与算法不符");
        } catch (IllegalArgumentException exception) {
            throw invalid("statementDigest 不是合法 Base64");
        }
        String requestedSigner = JsonNodes.text(body, "externalSignerId");
        if (requestedSigner == null && !"QR_CLAIM".equals(mode)) throw invalid("缺少 externalSignerId");
        if (requestedSigner != null) {
            Signer signer = requireSigner(requestedSigner);
            if (signer.state() != SignerState.READY) {
                throw new CaFailure("CA_SIGNER_NOT_READY", "签名人状态 " + signer.state() + " 不能签名");
            }
            if (signer.isRevoked()) throw new CaFailure("CA_CERTIFICATE_REVOKED", "签名人证书已吊销");
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(7, ChronoUnit.DAYS);
        String expires = JsonNodes.text(body, "expiresAt");
        if (expires != null) {
            try {
                expiresAt = Instant.parse(expires);
            } catch (RuntimeException exception) {
                throw invalid("expiresAt 不是 ISO-8601 时间");
            }
        }
        String externalOrderId = state.nextId("mock-order");
        Order order = new Order(externalOrderId, signingOrderId, longValue(body, "tenantId"),
                longValue(body, "organizationId"), requestedSigner,
                "QR_CLAIM".equals(mode) ? null : requestedSigner, mode, JsonNodes.text(body, "sceneCode"), algorithm,
                digest, JsonNodes.text(body, "subject"), JsonNodes.text(body, "patientDisplay"), now, expiresAt,
                OrderState.AWAITING_SIGNER, null, null, null, null, null, null, null, null);
        String raced = state.orderBySigningOrder().putIfAbsent(signingOrderId, externalOrderId);
        if (raced != null) return orderStatus(refresh(state.order(raced)));
        if ("AUTO".equals(mode) && granted(requestedSigner)) order = signOrder(order, requestedSigner);
        state.orders().put(externalOrderId, order);
        return orderStatus(order);
    }

    public ObjectNode queryOrder(JsonNode body) {
        Order order = state.order(required(body, "externalOrderId"));
        if (order == null) throw new CaFailure("CA_ORDER_NOT_FOUND", "未知任务 " + JsonNodes.text(body, "externalOrderId"));
        return orderStatus(refresh(order));
    }

    public ObjectNode queryBatch(JsonNode body) {
        JsonNode ids = body.get("externalOrderIds");
        if (ids == null || !ids.isArray() || ids.isEmpty()) throw invalid("缺少 externalOrderIds");
        if (ids.size() > BATCH_LIMIT) throw new CaFailure("CA_BATCH_LIMIT_EXCEEDED", "批量查询最多 " + BATCH_LIMIT + " 条");
        ObjectNode node = NODES.objectNode();
        ArrayNode orders = node.putArray("orders");
        for (JsonNode id : ids) {
            Order order = state.order(JsonNodes.text(id));
            if (order == null) {
                ObjectNode missing = orders.addObject();
                missing.put("externalOrderId", JsonNodes.text(id));
                missing.put("state", OrderState.FAILED.name());
                missing.put("errorCode", "CA_ORDER_NOT_FOUND");
                missing.put("message", "未知任务");
                missing.putNull("claimContent");
            } else {
                orders.add(orderStatus(refresh(order)));
            }
        }
        return node;
    }

    public ObjectNode fetch(JsonNode body) {
        Order order = state.order(required(body, "externalOrderId"));
        if (order == null) throw new CaFailure("CA_ORDER_NOT_FOUND", "未知任务");
        order = refresh(order);
        if (order.state() != OrderState.SIGNED) throw new CaFailure("CA_ORDER_NOT_SIGNED", "任务未签名，当前 " + order.state());
        ObjectNode node = NODES.objectNode();
        node.put("format", "SM2_ENVELOPE");
        node.put("signatureValue", order.signatureValue());
        node.put("certificate", order.certificate());
        node.put("signedEnvelope", order.envelope());
        node.put("signedAt", order.signedAt().toString());
        node.put("timestampAuthority", order.timestampAuthority());
        node.put("timestampToken", order.timestampToken());
        return node;
    }

    public ObjectNode cancel(JsonNode body) {
        Order order = state.order(required(body, "externalOrderId"));
        if (order == null) throw new CaFailure("CA_ORDER_NOT_FOUND", "未知任务");
        order = refresh(order);
        if (order.state() == OrderState.AWAITING_SIGNER) {
            order = order.withState(OrderState.CANCELLED, "已取消", null);
            state.orders().put(order.externalOrderId(), order);
        } else if (order.state() != OrderState.CANCELLED) {
            throw new CaFailure("CA_ORDER_NOT_CANCELLABLE", "仅待签状态可取消，当前 " + order.state());
        }
        return orderStatus(order);
    }

    /** 模拟医生在手机端确认 / 认领签名任务；{@code externalSignerId} 为空时用任务指定的签名人。 */
    public Order confirmOrder(String ref, String externalSignerId) {
        Order order = requireAwaiting(ref);
        String signerId = externalSignerId != null ? externalSignerId : order.requestedSignerId();
        if (signerId == null) throw invalid("QR_CLAIM 任务需要指定认领的 externalSignerId");
        if (order.requestedSignerId() != null && !order.requestedSignerId().equals(signerId)) {
            throw new CaFailure("CA_SIGNER_MISMATCH", "任务属于另一个签名人");
        }
        Order signed = signOrder(order, signerId);
        state.orders().put(signed.externalOrderId(), signed);
        return signed;
    }

    public Order rejectOrder(String ref) {
        Order order = requireAwaiting(ref).withState(OrderState.REJECTED, "签名人拒签", null);
        state.orders().put(order.externalOrderId(), order);
        return order;
    }

    public Order voidOrder(String ref) {
        Order order = requireOrderRef(ref);
        if (order.state() != OrderState.SIGNED) throw new CaFailure("CA_ORDER_STATE_CONFLICT", "仅已签名任务可作废");
        order = order.withState(OrderState.VOIDED_BY_SIGNER, "签后作废", null);
        state.orders().put(order.externalOrderId(), order);
        return order;
    }

    public Order requireOrderRef(String ref) {
        Order order = state.order(ref);
        if (order == null) {
            try {
                String external = state.orderBySigningOrder().get(Long.parseLong(ref));
                order = external == null ? null : state.order(external);
            } catch (NumberFormatException ignored) {
                // 不是 signingOrderId
            }
        }
        if (order == null) throw new CaFailure("CA_ORDER_NOT_FOUND", "未知任务 " + ref);
        return order;
    }

    private Order requireAwaiting(String ref) {
        Order order = refresh(requireOrderRef(ref));
        if (order.state() != OrderState.AWAITING_SIGNER) {
            throw new CaFailure("CA_ORDER_STATE_CONFLICT", "任务不是待签状态：" + order.state());
        }
        return order;
    }

    /** 惰性推进：到期置 EXPIRED；打开 autoConfirmOrders 时，待签任务在被查询时自动完成签名。 */
    private Order refresh(Order order) {
        if (order.state() != OrderState.AWAITING_SIGNER) return order;
        if (order.expiresAt().isBefore(Instant.now())) {
            Order expired = order.withState(OrderState.EXPIRED, "任务已过期", null);
            state.orders().put(order.externalOrderId(), expired);
            return expired;
        }
        if (configs.current().autoConfirmOrders() && order.requestedSignerId() != null) {
            Order signed = signOrder(order, order.requestedSignerId());
            state.orders().put(signed.externalOrderId(), signed);
            return signed;
        }
        return order;
    }

    /** 厂商侧签名：按签名人场景产出 SIGNED / FAILED / REJECTED；封装 = 内嵌摘要的 JSON，SM3withSM2 签封装原文。 */
    private Order signOrder(Order order, String signerId) {
        Signer signer = state.signer(signerId);
        if (signer == null || signer.state() != SignerState.READY || signer.keys() == null) {
            return order.withState(OrderState.FAILED, "签名人未就绪", "CA_SIGNER_NOT_READY");
        }
        if (signer.isRevoked()) return order.withState(OrderState.FAILED, "签名人证书已吊销", "CA_CERTIFICATE_REVOKED");
        if (signer.behavior() == Behavior.SIGN_FAIL) return order.withState(OrderState.FAILED, "厂商侧签名失败", "CA_SIGN_FAILED");
        if (signer.behavior() == Behavior.SIGN_REJECT) return order.withState(OrderState.REJECTED, "签名人拒签", null);
        String digest = order.digest();
        if (signer.behavior() == Behavior.BAD_ENVELOPE) {
            byte[] raw = CaCrypto.fromB64(digest);
            raw[0] ^= 0x5A;
            digest = CaCrypto.b64(raw);
        }
        Map<String, String> envelope = new LinkedHashMap<>();
        envelope.put("vendor", properties.vendorCode());
        envelope.put("order", order.externalOrderId());
        envelope.put("digestAlgorithm", order.digestAlgorithm());
        envelope.put("digest", digest);
        String envelopeJson = mapper.writeValueAsString(envelope);
        byte[] signature = CaCrypto.sign(signer.keys().getPrivate(), envelopeJson.getBytes(StandardCharsets.UTF_8));
        if (signer.behavior() == Behavior.BAD_SIGNATURE) signature[signature.length - 1] ^= 0x55;
        CaAuthority.Stamp stamp = authority.stamp(signature);
        return order.signed(signerId, envelopeJson, CaCrypto.b64(signature),
                CaCrypto.b64(CaCrypto.encoded(signer.certificate())), Instant.now().truncatedTo(ChronoUnit.SECONDS),
                stamp.authority(), stamp.token());
    }

    private ObjectNode orderStatus(Order order) {
        ObjectNode node = NODES.objectNode();
        node.put("externalOrderId", order.externalOrderId());
        node.put("state", order.state().name());
        if ("QR_CLAIM".equals(order.mode()) && order.state() == OrderState.AWAITING_SIGNER) {
            node.put("claimContent", "mock-ca://claim/" + order.externalOrderId());
        } else {
            node.putNull("claimContent");
        }
        if (order.errorCode() == null) node.putNull("errorCode");
        else node.put("errorCode", order.errorCode());
        if (order.message() == null) node.putNull("message");
        else node.put("message", order.message());
        return node;
    }

    // ==================== 自动签授权 ====================

    public ObjectNode grantRequest(JsonNode body) {
        String id = required(body, "externalSignerId");
        Signer signer = requireSigner(id);
        if (signer.state() != SignerState.READY) throw new CaFailure("CA_SIGNER_NOT_READY", "签名人状态 " + signer.state());
        long seconds = longValue(body, "durationSeconds") == null ? 4 * 3600L : longValue(body, "durationSeconds");
        Instant expiresAt = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.SECONDS);
        state.grants().put(id, new Grant(GrantState.PENDING, seconds, null, expiresAt));
        ObjectNode node = NODES.objectNode();
        node.put("qrContent", "mock-ca://grant/" + id);
        node.put("expiresAt", expiresAt.toString());
        return node;
    }

    public ObjectNode grantQuery(JsonNode body) {
        String id = required(body, "externalSignerId");
        requireSigner(id);
        ObjectNode node = NODES.objectNode();
        node.put("state", grantState(id).name());
        return node;
    }

    public ObjectNode grantRevoke(JsonNode body) {
        String id = required(body, "externalSignerId");
        requireSigner(id);
        state.grants().remove(id);
        ObjectNode node = NODES.objectNode();
        node.put("state", GrantState.NONE.name());
        return node;
    }

    /** 模拟医生在手机端批准自动签。 */
    public void approveGrant(String externalSignerId) {
        Grant grant = state.grants().get(externalSignerId);
        if (grant == null || grant.state() != GrantState.PENDING) throw new CaFailure("CA_GRANT_NOT_PENDING", "没有待批准的自动签授权");
        state.grants().put(externalSignerId, new Grant(GrantState.GRANTED, grant.durationSeconds(),
                Instant.now().plusSeconds(grant.durationSeconds()), grant.requestExpiresAt()));
    }

    public void refuseGrant(String externalSignerId) {
        Grant grant = state.grants().get(externalSignerId);
        if (grant == null || grant.state() != GrantState.PENDING) throw new CaFailure("CA_GRANT_NOT_PENDING", "没有待批准的自动签授权");
        state.grants().put(externalSignerId, new Grant(GrantState.REFUSED, grant.durationSeconds(), null,
                grant.requestExpiresAt()));
    }

    private GrantState grantState(String id) {
        Grant grant = state.grants().get(id);
        if (grant == null) return GrantState.NONE;
        if (grant.state() == GrantState.PENDING) {
            if (grant.requestExpiresAt().isBefore(Instant.now())) {
                state.grants().remove(id);
                return GrantState.NONE;
            }
            if (configs.current().autoConfirmOrders()) {
                approveGrant(id);
                return GrantState.GRANTED;
            }
        }
        if (grant.state() == GrantState.GRANTED && grant.until().isBefore(Instant.now())) {
            state.grants().remove(id);
            return GrantState.NONE;
        }
        return grant.state();
    }

    private boolean granted(String id) {
        return id != null && grantState(id) == GrantState.GRANTED;
    }

    // ==================== 时间戳 / 信任材料 / 验签 ====================

    public ObjectNode stamp(JsonNode body) {
        CaAuthority.Stamp stamp = authority.stamp(decode(required(body, "signatureValue"), "signatureValue"));
        ObjectNode node = NODES.objectNode();
        node.put("authority", stamp.authority());
        node.put("token", stamp.token());
        node.put("time", stamp.time().toString());
        return node;
    }

    public ObjectNode verifyStamp(JsonNode body) {
        var time = authority.verifyStamp(required(body, "authority"), required(body, "token"),
                decode(required(body, "signatureValue"), "signatureValue"));
        ObjectNode node = NODES.objectNode();
        node.put("valid", time.isPresent());
        if (time.isPresent()) node.put("time", time.get().toString());
        else node.putNull("time");
        return node;
    }

    public ObjectNode trustAnchors() {
        ObjectNode node = NODES.objectNode();
        ArrayNode certificates = node.putArray("certificates");
        certificates.add(CaCrypto.b64(CaCrypto.encoded(authority.root())));
        node.put("timestampAuthority", authority.authorityName());
        node.put("timestampCertificate", CaCrypto.b64(CaCrypto.encoded(authority.tsaCertificate())));
        node.put("rootSubject", authority.rootDn());
        node.put("rootFingerprint", CaCrypto.fingerprint(authority.root()));
        return node;
    }

    public ObjectNode envelopeBinds(JsonNode body) {
        ObjectNode node = NODES.objectNode();
        node.put("binds", envelopeBinds(required(body, "envelope"), required(body, "digestAlgorithm"),
                decode(required(body, "statementDigest"), "statementDigest")));
        return node;
    }

    /** 厂商验签（仅供交叉核对，RHN 以本地验签为准）。 */
    public ObjectNode verifySignature(JsonNode body) {
        String format = required(body, "format").toUpperCase();
        if (!format.equals("SM2_STATEMENT") && !format.equals("SM2_ENVELOPE")) throw invalid("未知 format: " + format);
        X509Certificate certificate;
        byte[] signature;
        try {
            certificate = CaCrypto.certificate(required(body, "certificate"));
            signature = CaCrypto.fromB64(required(body, "signatureValue"));
        } catch (IllegalArgumentException exception) {
            return verdict(false, "CA_SIGNATURE_MALFORMED", "签名值或证书格式无效", null);
        }
        Instant signedAt = Instant.now();
        String at = JsonNodes.text(body, "signedAt");
        if (at != null) {
            try {
                signedAt = Instant.parse(at);
            } catch (RuntimeException exception) {
                throw invalid("signedAt 不是 ISO-8601 时间");
            }
        }
        Signer signer = state.signerByFingerprint(CaCrypto.fingerprint(certificate));
        String failure = certificateFailure(certificate, signedAt, signer);
        if (failure != null) return verdict(false, failure, message(failure), signer);
        byte[] statement = body.has("statement") ? decode(required(body, "statement"), "statement") : null;
        boolean ok;
        if (format.equals("SM2_STATEMENT")) {
            if (statement == null) throw invalid("SM2_STATEMENT 需要 statement");
            ok = CaCrypto.verify(certificate.getPublicKey(), statement, signature);
        } else {
            String envelope = required(body, "signedEnvelope");
            String algorithm = required(body, "digestAlgorithm");
            if (!CaCrypto.supportsDigest(algorithm)) throw invalid("不支持的 digestAlgorithm: " + algorithm);
            byte[] digest = body.has("statementDigest") ? decode(required(body, "statementDigest"), "statementDigest")
                    : statement == null ? null : CaCrypto.digest(algorithm, statement);
            if (digest == null) throw invalid("SM2_ENVELOPE 需要 statement 或 statementDigest");
            ok = envelopeBinds(envelope, algorithm, digest)
                    && CaCrypto.verify(certificate.getPublicKey(), envelope.getBytes(StandardCharsets.UTF_8), signature);
        }
        if (ok && signer != null && signer.behavior() == Behavior.VERIFY_FAIL) ok = false;
        return ok ? verdict(true, null, null, signer)
                : verdict(false, "CA_SIGNATURE_INVALID", message("CA_SIGNATURE_INVALID"), signer);
    }

    public ObjectNode certificateStatus(JsonNode body) {
        X509Certificate certificate = parseCertificate(required(body, "certificate"));
        Signer signer = state.signerByFingerprint(CaCrypto.fingerprint(certificate));
        String status;
        if (!authority.chainsToRoot(certificate)) status = "UNTRUSTED";
        else if (signer != null && signer.isRevoked()) status = "REVOKED";
        else if (!CaCrypto.validAt(certificate, Instant.now())) status = "EXPIRED";
        else status = signer == null ? "UNKNOWN" : "GOOD";
        ObjectNode node = NODES.objectNode();
        node.put("status", status);
        node.put("fingerprint", CaCrypto.fingerprint(certificate));
        node.put("notAfter", certificate.getNotAfter().toInstant().toString());
        if (signer != null && signer.isRevoked()) {
            node.put("revokedAt", signer.revokedAt().toString());
            node.put("revokeReason", signer.revokeReason());
        } else {
            node.putNull("revokedAt");
            node.putNull("revokeReason");
        }
        return node;
    }

    private boolean envelopeBinds(String envelope, String algorithm, byte[] digest) {
        try {
            JsonNode node = mapper.readTree(envelope);
            return properties.vendorCode().equals(node.get("vendor").asString())
                    && algorithm.equalsIgnoreCase(node.get("digestAlgorithm").asString())
                    && MessageDigest.isEqual(digest, CaCrypto.fromB64(node.get("digest").asString()));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** 证书层面的拒绝原因（顺序与 RHN {@code PersonalSignatureVerifier} 一致，另加吊销检查）。 */
    private String certificateFailure(X509Certificate certificate, Instant at, Signer signer) {
        if (!authority.chainsToRoot(certificate)) return "CA_CERTIFICATE_UNTRUSTED";
        if (!CaCrypto.validAt(certificate, at)) return "CA_CERTIFICATE_EXPIRED";
        if (!CaCrypto.allowsSigning(certificate)) return "CA_CERTIFICATE_USAGE_INVALID";
        if (signer != null && signer.isRevoked()) return "CA_CERTIFICATE_REVOKED";
        return null;
    }

    private static String message(String code) {
        return switch (code) {
            case "CA_CERTIFICATE_UNTRUSTED" -> "证书不是受信任 CA 签发";
            case "CA_CERTIFICATE_EXPIRED" -> "证书不在有效期内";
            case "CA_CERTIFICATE_USAGE_INVALID" -> "证书不允许用于数字签名";
            case "CA_CERTIFICATE_REVOKED" -> "证书已吊销";
            default -> "签名值校验失败";
        };
    }

    private ObjectNode verdict(boolean valid, String code, String message, Signer signer) {
        ObjectNode node = NODES.objectNode();
        node.put("valid", valid);
        if (code == null) node.putNull("reasonCode");
        else node.put("reasonCode", code);
        if (message == null) node.putNull("message");
        else node.put("message", message);
        if (signer == null) node.putNull("externalSignerId");
        else node.put("externalSignerId", signer.externalSignerId());
        return node;
    }

    // ==================== 管理端：证书材料与 UKey 本地签名 ====================

    /** 模拟 UKey 中间件：SM3withSM2 直接签浏览器要求签的字节（登录挑战 / 业务待签原文）。 */
    public ObjectNode clientSign(String externalSignerId, String dataBase64) {
        Signer signer = requireSigner(externalSignerId);
        if (signer.keys() == null) throw new CaFailure("CA_SIGNER_NOT_READY", "签名人尚无证书");
        byte[] data = decode(dataBase64, "data");
        byte[] signature = CaCrypto.sign(signer.keys().getPrivate(), data);
        state.clientSigns().add(new CaMockState.ClientSign(Instant.now().toString(), externalSignerId,
                CaCrypto.b64(CaCrypto.digest(CaCrypto.SM3, data)), CaCrypto.b64(signature)));
        ObjectNode node = NODES.objectNode();
        node.put("signatureValue", CaCrypto.b64(signature));
        node.put("certificate", CaCrypto.b64(CaCrypto.encoded(signer.certificate())));
        return node;
    }

    /** 导出测试证书与私钥（PKCS#8），供联调把 UKey / 本地签名替身接到 RHN 前端或脚本。 */
    public ObjectNode material(String externalSignerId) {
        Signer signer = requireSigner(externalSignerId);
        if (signer.keys() == null) throw new CaFailure("CA_SIGNER_NOT_READY", "签名人尚无证书");
        byte[] cert = CaCrypto.encoded(signer.certificate());
        byte[] key = signer.keys().getPrivate().getEncoded();
        ObjectNode node = NODES.objectNode();
        node.put("externalSignerId", signer.externalSignerId());
        node.put("fingerprint", CaCrypto.fingerprint(signer.certificate()));
        node.put("certificateBase64", CaCrypto.b64(cert));
        node.put("certificatePem", CaCrypto.pem("CERTIFICATE", cert));
        node.put("privateKeyPkcs8Base64", CaCrypto.b64(key));
        node.put("privateKeyPem", CaCrypto.pem("PRIVATE KEY", key));
        return node;
    }

    public ObjectNode signerView(Signer signer) {
        ObjectNode node = enrollment(signer);
        node.put("name", signer.name());
        node.put("behavior", signer.behavior().name());
        node.put("revoked", signer.isRevoked());
        if (signer.userId() == null) node.putNull("userId");
        else node.put("userId", signer.userId());
        if (signer.certificate() != null) node.put("fingerprint", CaCrypto.fingerprint(signer.certificate()));
        return node;
    }

    public ObjectNode orderView(Order order) {
        ObjectNode node = orderStatus(order);
        node.put("signingOrderId", order.signingOrderId());
        node.put("mode", order.mode());
        node.put("sceneCode", order.sceneCode());
        node.put("signerId", order.signerId());
        node.put("requestedSignerId", order.requestedSignerId());
        node.put("digestAlgorithm", order.digestAlgorithm());
        node.put("digest", order.digest());
        node.put("subject", order.subject());
        node.put("patientDisplay", order.patientDisplay());
        node.put("createdAt", order.createdAt().toString());
        node.put("expiresAt", order.expiresAt().toString());
        if (order.signedAt() != null) node.put("signedAt", order.signedAt().toString());
        return node;
    }

    public List<ObjectNode> orderViews() {
        List<ObjectNode> views = new ArrayList<>();
        for (Order order : state.allOrders()) views.add(orderView(refresh(order)));
        return views;
    }

    /** 请求对应的签名人场景（用于签名人维度的 SLOW / TIMEOUT / HTTP500 / BADJSON 注入）。 */
    public Behavior behaviorOf(String op, JsonNode body) {
        if (body == null) return Behavior.NORMAL;
        if (op.equals("signers/enroll")) return scenarios.behaviorFor(JsonNodes.text(body, "identityNumber"));
        String signerId = JsonNodes.text(body, "externalSignerId");
        if (signerId == null) {
            String orderId = JsonNodes.text(body, "externalOrderId");
            Order order = orderId == null ? null : state.order(orderId);
            if (order != null) signerId = order.signerId() != null ? order.signerId() : order.requestedSignerId();
            String loginId = JsonNodes.text(body, "externalRequestId");
            Login login = loginId == null ? null : state.logins().get(loginId);
            if (login != null) signerId = login.signerId();
        }
        Signer signer = state.signer(signerId);
        return signer == null ? Behavior.NORMAL : signer.behavior();
    }

    // ==================== 工具 ====================

    private Signer requireSigner(String id) {
        Signer signer = state.signer(id);
        if (signer == null) throw new CaFailure("CA_SIGNER_NOT_FOUND", "未知签名人 " + id);
        return signer;
    }

    private static X509Certificate parseCertificate(String value) {
        try {
            return CaCrypto.certificate(value);
        } catch (IllegalArgumentException exception) {
            throw invalid("certificate 不是合法的 Base64 DER 证书");
        }
    }

    private static byte[] decode(String value, String field) {
        try {
            return CaCrypto.fromB64(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(field + " 不是合法 Base64");
        }
    }

    private static String required(JsonNode body, String field) {
        String value = JsonNodes.text(body, field);
        if (value == null) throw invalid("缺少 " + field);
        return value;
    }

    private static Long longValue(JsonNode body, String field) {
        String value = JsonNodes.text(body, field);
        if (value == null) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw invalid(field + " 必须是数字");
        }
    }

    private static CaFailure invalid(String message) {
        return new CaFailure("CA_INVALID_ARGUMENT", message);
    }
}
