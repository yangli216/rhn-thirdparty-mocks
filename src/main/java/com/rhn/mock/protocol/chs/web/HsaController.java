package com.rhn.mock.protocol.chs.web;

import com.rhn.mock.core.MockHttpResponse;
import com.rhn.mock.protocol.chs.ChsMockEngine;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 医保业务端点：路径可配置，默认 {@code POST /hsa/api}。 */
@RestController
public class HsaController {
    private final ChsMockEngine engine;

    public HsaController(ChsMockEngine engine) {
        this.engine = engine;
    }

    @PostMapping(path = "${mock.chs.business-path:/hsa/api}")
    public ResponseEntity<String> hsa(@RequestBody(required = false) String body,
                                      @RequestHeader(value = "X-Token", required = false) String xToken) {
        MockHttpResponse respond = engine.handle(body == null ? "" : body, xToken);
        return ResponseEntity.status(respond.httpStatus())
                .contentType(MediaType.valueOf(respond.contentType()))
                .body(respond.body());
    }
}
