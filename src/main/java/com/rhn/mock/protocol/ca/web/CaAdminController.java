package com.rhn.mock.protocol.ca.web;

import com.rhn.mock.core.JsonNodes;
import com.rhn.mock.protocol.ca.CaAuthority;
import com.rhn.mock.protocol.ca.CaCrypto;
import com.rhn.mock.protocol.ca.CaFailure;
import com.rhn.mock.protocol.ca.CaMockState;
import com.rhn.mock.protocol.ca.CaScenarioCatalog;
import com.rhn.mock.protocol.ca.CaVendorService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * CA 测试辅助端点 {@code /mock/ca/**}：导出测试 CA 根证书 / 时间戳证书、签发与导出测试证书私钥、
 * 吊销、审核，以及模拟签名人侧动作（手机确认登录 / 签名任务、批准自动签、UKey 本地签名）和查看签名流水。
 * 不受 {@code X-Client-*} 凭据保护——它是测试人员的遥控器，不是厂商接口；服务默认只监听 127.0.0.1。
 */
@RestController
public class CaAdminController {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final CaVendorService service;
    private final CaMockState state;
    private final CaAuthority authority;
    private final CaScenarioCatalog scenarios;

    public CaAdminController(CaVendorService service, CaMockState state, CaAuthority authority,
                             CaScenarioCatalog scenarios) {
        this.service = service;
        this.state = state;
        this.authority = authority;
        this.scenarios = scenarios;
    }

    // ---- 测试 CA 材料 ----

    @GetMapping(path = "/mock/ca/root", produces = "text/plain;charset=UTF-8")
    public String root() {
        return CaCrypto.pem("CERTIFICATE", CaCrypto.encoded(authority.root()));
    }

    @GetMapping(path = "/mock/ca/tsa", produces = "text/plain;charset=UTF-8")
    public String tsa() {
        return CaCrypto.pem("CERTIFICATE", CaCrypto.encoded(authority.tsaCertificate()));
    }

    // ---- 签名人 ----

    @GetMapping("/mock/ca/signers")
    public ResponseEntity<String> signers() {
        ArrayNode array = NODES.arrayNode();
        state.signers().values().stream()
                .sorted(java.util.Comparator.comparing(CaMockState.Signer::createdAt)
                        .thenComparing(CaMockState.Signer::externalSignerId))
                .forEach(signer -> array.add(service.signerView(signer)));
        return json(array);
    }

    /** 直接签发测试人员：{@code {"name","userId","scenario"}}，scenario 为 {@link CaScenarioCatalog.Behavior} 名。 */
    @PostMapping("/mock/ca/signers")
    public ResponseEntity<String> issue(@RequestBody(required = false) String body) {
        JsonNode node = parse(body);
        String name = JsonNodes.text(node, "name");
        if (name == null) throw new CaFailure("CA_INVALID_ARGUMENT", "缺少 name");
        String userId = JsonNodes.text(node, "userId");
        String id = userId == null ? state.nextId("mock-signer") : "mock-" + userId;
        if (state.signer(id) != null) throw new CaFailure("CA_SIGNER_EXISTS", "签名人已存在 " + id);
        CaMockState.Signer signer = service.newSigner(id, userId == null ? null : Long.valueOf(userId), null, name,
                JsonNodes.text(node, "mobile"), scenarios.parse(JsonNodes.text(node, "scenario")));
        state.signers().put(id, signer);
        return json(service.signerView(signer));
    }

    @GetMapping("/mock/ca/signers/{id}/material")
    public ResponseEntity<String> material(@PathVariable String id) {
        return json(service.material(id));
    }

    @PostMapping("/mock/ca/signers/{id}/approve")
    public ResponseEntity<String> approve(@PathVariable String id) {
        return json(service.signerView(service.approve(id)));
    }

    @PostMapping("/mock/ca/signers/{id}/revoke")
    public ResponseEntity<String> revoke(@PathVariable String id, @RequestBody(required = false) String body) {
        return json(service.signerView(service.revoke(id, JsonNodes.text(parse(body), "reason"))));
    }

    /** 模拟 UKey 中间件：{@code {"data":"<base64 待签字节>"}} → 签名值 + 证书。 */
    @PostMapping("/mock/ca/signers/{id}/client-sign")
    public ResponseEntity<String> clientSign(@PathVariable String id, @RequestBody(required = false) String body) {
        String data = JsonNodes.text(parse(body), "data");
        if (data == null) throw new CaFailure("CA_INVALID_ARGUMENT", "缺少 data");
        return json(service.clientSign(id, data));
    }

    @GetMapping("/mock/ca/client-signs")
    public ResponseEntity<String> clientSigns() {
        return json(mapper.valueToTree(state.clientSigns()));
    }

    // ---- 签名任务（签名流水） ----

    @GetMapping("/mock/ca/orders")
    public ResponseEntity<String> orders() {
        ArrayNode array = NODES.arrayNode();
        service.orderViews().forEach(array::add);
        return json(array);
    }

    /** {@code ref} 可以是外部任务号或 RHN 的 signingOrderId；body 可带 {@code externalSignerId}（QR 认领）。 */
    @PostMapping("/mock/ca/orders/{ref}/confirm")
    public ResponseEntity<String> confirmOrder(@PathVariable String ref, @RequestBody(required = false) String body) {
        return json(service.orderView(service.confirmOrder(ref, JsonNodes.text(parse(body), "externalSignerId"))));
    }

    @PostMapping("/mock/ca/orders/{ref}/reject")
    public ResponseEntity<String> rejectOrder(@PathVariable String ref) {
        return json(service.orderView(service.rejectOrder(ref)));
    }

    @PostMapping("/mock/ca/orders/{ref}/void")
    public ResponseEntity<String> voidOrder(@PathVariable String ref) {
        return json(service.orderView(service.voidOrder(ref)));
    }

    // ---- 登录 / 挑战 ----

    @PostMapping("/mock/ca/logins/{id}/confirm")
    public ResponseEntity<String> confirmLogin(@PathVariable String id, @RequestBody(required = false) String body) {
        String signer = JsonNodes.text(parse(body), "externalSignerId");
        if (signer == null) throw new CaFailure("CA_INVALID_ARGUMENT", "缺少 externalSignerId");
        return json(mapper.valueToTree(service.confirmLogin(id, signer)));
    }

    @PostMapping("/mock/ca/logins/{id}/reject")
    public ResponseEntity<String> rejectLogin(@PathVariable String id) {
        return json(mapper.valueToTree(service.rejectLogin(id)));
    }

    @PostMapping("/mock/ca/logins/{id}/expire")
    public ResponseEntity<String> expireLogin(@PathVariable String id) {
        service.expireLogin(id);
        return json(NODES.objectNode().put("status", "OK"));
    }

    @PostMapping("/mock/ca/challenges/{id}/expire")
    public ResponseEntity<String> expireChallenge(@PathVariable String id) {
        service.expireChallenge(id);
        return json(NODES.objectNode().put("status", "OK"));
    }

    // ---- 自动签授权 ----

    @PostMapping("/mock/ca/grants/{signerId}/approve")
    public ResponseEntity<String> approveGrant(@PathVariable String signerId) {
        service.approveGrant(signerId);
        return json(NODES.objectNode().put("status", "OK"));
    }

    @PostMapping("/mock/ca/grants/{signerId}/refuse")
    public ResponseEntity<String> refuseGrant(@PathVariable String signerId) {
        service.refuseGrant(signerId);
        return json(NODES.objectNode().put("status", "OK"));
    }

    @ExceptionHandler(CaFailure.class)
    public ResponseEntity<String> failed(CaFailure failure) {
        ObjectNode node = NODES.objectNode();
        node.put("errorCode", failure.errorCode());
        node.put("message", failure.getMessage());
        HttpStatus status = failure.errorCode().endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND
                : "CA_INVALID_ARGUMENT".equals(failure.errorCode()) ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).contentType(MediaType.valueOf("application/json;charset=UTF-8"))
                .body(mapper.writeValueAsString(node));
    }

    private JsonNode parse(String body) {
        return body == null || body.isBlank() ? NODES.objectNode() : mapper.readTree(body);
    }

    private ResponseEntity<String> json(JsonNode node) {
        return ResponseEntity.ok().contentType(MediaType.valueOf("application/json;charset=UTF-8"))
                .body(mapper.writeValueAsString(node));
    }
}
