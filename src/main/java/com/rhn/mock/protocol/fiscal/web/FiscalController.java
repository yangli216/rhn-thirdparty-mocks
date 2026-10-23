package com.rhn.mock.protocol.fiscal.web;

import com.rhn.mock.core.MockHttpResponse;
import com.rhn.mock.protocol.fiscal.FiscalMockEngine;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 财政电子票据业务端点：{@code POST <base-path>/v1/receipts/{issue|query|void|redFlush}}，默认基址 {@code /fiscal}。
 * 协议见 docs/fiscal-mock-protocol.md。
 */
@RestController
public class FiscalController {
    private final FiscalMockEngine engine;

    public FiscalController(FiscalMockEngine engine) {
        this.engine = engine;
    }

    @PostMapping(path = "${mock.fiscal.base-path:/fiscal}/v1/receipts/{op}")
    public ResponseEntity<String> operate(@PathVariable String op,
                                          @RequestBody(required = false) String body,
                                          @RequestHeader(value = "X-Client-Id", required = false) String clientId,
                                          @RequestHeader(value = "X-Client-Secret", required = false) String secret) {
        MockHttpResponse respond = engine.handle(op, body == null ? "" : body, clientId, secret);
        return ResponseEntity.status(respond.httpStatus())
                .contentType(MediaType.valueOf(respond.contentType()))
                .body(respond.body());
    }
}
