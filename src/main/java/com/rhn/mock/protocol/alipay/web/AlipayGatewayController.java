package com.rhn.mock.protocol.alipay.web;

import com.rhn.mock.core.MockHttpResponse;
import com.rhn.mock.protocol.alipay.AlipayGatewayService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 支付宝网关端点：{@code POST /alipay/gateway.do}。
 *
 * <p>真实 SDK 把协议参数放在 query、{@code biz_content} 放在 form body，因此用
 * {@code @RequestParam Map} 同时绑定两处，路径可配 {@code mock.alipay.gateway-path}。
 */
@RestController
public class AlipayGatewayController {
    private final AlipayGatewayService service;

    public AlipayGatewayController(AlipayGatewayService service) {
        this.service = service;
    }

    @PostMapping(path = "${mock.alipay.gateway-path:/alipay/gateway.do}")
    public ResponseEntity<String> gateway(@RequestParam Map<String, String> params) {
        MockHttpResponse response = service.handle(params);
        return ResponseEntity.status(response.httpStatus())
                .contentType(MediaType.valueOf(response.contentType()))
                .body(response.body());
    }
}
