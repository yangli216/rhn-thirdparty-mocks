package com.rhn.mock.core.web;

import com.rhn.mock.core.ExchangeLog;
import com.rhn.mock.core.spi.MockProtocol;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 管理端点：{@code /mock/reset}、{@code /mock/requests}、{@code /mock/config}、{@code /mock/health}。
 *
 * <p>多协议化：reset 遍历全部 {@link MockProtocol}；config 默认返回 CHS 视图（与单协议版本逐字段等价），
 * {@code ?protocol=<id>} 返回该协议，{@code ?protocol=all} 返回嵌套对象。
 */
@RestController
public class MockAdminController {
    /** CHS 兼容口径：{@code /mock/requests} 无 protocol 参数时的默认协议。 */
    private static final String CHS = "chs";
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final List<MockProtocol> protocols;
    private final ExchangeLog log;

    public MockAdminController(List<MockProtocol> protocols, ExchangeLog log) {
        this.protocols = protocols;
        this.log = log;
    }

    @PostMapping("/mock/reset")
    public ResponseEntity<String> reset() {
        for (MockProtocol protocol : protocols) protocol.reset();
        log.clear();
        ObjectNode node = NODES.objectNode().put("status", "OK");
        ArrayNode ids = node.putArray("reset");
        for (MockProtocol protocol : protocols) ids.add(protocol.id());
        return json(node);
    }

    @GetMapping("/mock/requests")
    public ResponseEntity<String> requests(@RequestParam(value = "infno", required = false) String infno,
                                           @RequestParam(value = "protocol", required = false) String protocol,
                                           @RequestParam(value = "op", required = false) String op,
                                           @RequestParam(value = "limit", required = false) Integer limit) {
        String filterProtocol = protocol;
        String filterOp = op;
        if (infno != null && !infno.isBlank()) {
            filterProtocol = CHS;
            filterOp = infno;
        }
        ArrayNode array = NODES.arrayNode();
        for (ExchangeLog.Entry entry : log.query(filterProtocol, filterOp, limit == null ? 100 : limit)) {
            ObjectNode node = array.addObject();
            node.put("time", entry.time());
            node.put("protocol", entry.protocol());
            node.put("op", entry.op());
            node.put("refId", entry.refId());
            node.put("httpStatus", entry.httpStatus());
            node.put("request", entry.request());
            node.put("response", entry.response());
            if (CHS.equals(entry.protocol())) {
                // 兼容单协议版本：CHS 的操作码即 infno，参考号即 msgid。
                node.put("infno", entry.op());
                node.put("msgid", entry.refId());
            }
        }
        return json(array);
    }

    @GetMapping("/mock/config")
    public ResponseEntity<String> config(@RequestParam(value = "protocol", required = false) String protocol) {
        return json(configView(protocol));
    }

    @PutMapping("/mock/config")
    public ResponseEntity<String> updateConfig(@RequestParam(value = "protocol", required = false) String protocol,
                                               @RequestBody(required = false) String body) {
        JsonNode patch = body != null && !body.isBlank() ? mapper.readTree(body) : null;
        if (isAll(protocol)) {
            ObjectNode all = NODES.objectNode();
            for (MockProtocol plugin : protocols) {
                all.set(plugin.id(), patch == null ? plugin.configView() : plugin.applyConfig(patch));
            }
            return json(all);
        }
        MockProtocol plugin = target(protocol);
        return json(patch == null ? plugin.configView() : plugin.applyConfig(patch));
    }

    @GetMapping("/mock/health")
    public ResponseEntity<String> health() {
        ObjectNode node = NODES.objectNode();
        node.put("status", "UP");
        node.put("transactions", log.size());
        ArrayNode ids = node.putArray("protocols");
        for (MockProtocol protocol : protocols) ids.add(protocol.id());
        return json(node);
    }

    private ObjectNode configView(String protocol) {
        if (isAll(protocol)) {
            ObjectNode all = NODES.objectNode();
            for (MockProtocol plugin : protocols) all.set(plugin.id(), plugin.configView());
            return all;
        }
        return target(protocol).configView();
    }

    private MockProtocol target(String protocol) {
        String id = protocol == null || protocol.isBlank() ? CHS : protocol;
        return protocols.stream()
                .filter(plugin -> plugin.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知协议: " + id));
    }

    private static boolean isAll(String protocol) {
        return "all".equalsIgnoreCase(protocol);
    }

    private ResponseEntity<String> json(JsonNode node) {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/json;charset=UTF-8"))
                .body(mapper.writeValueAsString(node));
    }
}
