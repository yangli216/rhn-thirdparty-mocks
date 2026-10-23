package com.rhn.mock.protocol.ca.web;

import com.rhn.mock.core.MockHttpResponse;
import com.rhn.mock.protocol.ca.CaMockEngine;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * CA 厂商业务端点：{@code POST <base-path>/v1/<组>/<操作>}，默认基址 {@code /ca}。
 * 操作清单见 {@link CaMockEngine#OPERATIONS}，协议见 docs/ca-mock-protocol.md。
 */
@RestController
public class CaController {
    private final CaMockEngine engine;
    private final String prefix;

    public CaController(CaMockEngine engine, @Value("${mock.ca.base-path:/ca}") String basePath) {
        this.engine = engine;
        this.prefix = basePath + "/v1/";
    }

    @PostMapping(path = "${mock.ca.base-path:/ca}/v1/**")
    public ResponseEntity<String> operate(HttpServletRequest request,
                                          @RequestBody(required = false) String body,
                                          @RequestHeader(value = "X-Client-Id", required = false) String clientId,
                                          @RequestHeader(value = "X-Client-Secret", required = false) String secret) {
        String uri = request.getRequestURI();
        String op = uri.startsWith(prefix) ? uri.substring(prefix.length()) : uri;
        MockHttpResponse respond = engine.handle(op, body, clientId, secret);
        return ResponseEntity.status(respond.httpStatus())
                .contentType(MediaType.valueOf(respond.contentType()))
                .body(respond.body());
    }
}
