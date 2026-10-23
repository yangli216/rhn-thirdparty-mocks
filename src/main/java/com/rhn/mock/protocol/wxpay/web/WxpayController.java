package com.rhn.mock.protocol.wxpay.web;

import com.rhn.mock.core.MockHttpResponse;
import com.rhn.mock.protocol.wxpay.WxpayGatewayService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信支付 API v2 端点：{@code /wxpay/pay/micropay}、{@code /wxpay/pay/orderquery}、
 * {@code /wxpay/secapi/pay/refundv2}、{@code /wxpay/pay/refundquery}。
 *
 * <p>请求体是 XML 原文，因此用 {@code @RequestBody String}；基址可配 {@code mock.wxpay.base-path}。
 * 注意退款路径是 {@code /secapi/pay/refundv2}（WxJava 的 {@code refundV2()} 拼此路径）。
 */
@RestController
public class WxpayController {
    private final WxpayGatewayService service;

    public WxpayController(WxpayGatewayService service) {
        this.service = service;
    }

    @PostMapping(path = "${mock.wxpay.base-path:/wxpay}/pay/micropay")
    public ResponseEntity<String> micropay(@RequestBody String xml) {
        return reply(service.handle(WxpayGatewayService.MICROPAY, xml));
    }

    @PostMapping(path = "${mock.wxpay.base-path:/wxpay}/pay/orderquery")
    public ResponseEntity<String> orderQuery(@RequestBody String xml) {
        return reply(service.handle(WxpayGatewayService.ORDER_QUERY, xml));
    }

    @PostMapping(path = "${mock.wxpay.base-path:/wxpay}/secapi/pay/refundv2")
    public ResponseEntity<String> refund(@RequestBody String xml) {
        return reply(service.handle(WxpayGatewayService.REFUND, xml));
    }

    @PostMapping(path = "${mock.wxpay.base-path:/wxpay}/pay/refundquery")
    public ResponseEntity<String> refundQuery(@RequestBody String xml) {
        return reply(service.handle(WxpayGatewayService.REFUND_QUERY, xml));
    }

    @PostMapping(path = "${mock.wxpay.base-path:/wxpay}/pay/downloadbill")
    public ResponseEntity<String> downloadBill(@RequestBody String xml) {
        return reply(service.handle(WxpayGatewayService.DOWNLOAD_BILL, xml));
    }

    private ResponseEntity<String> reply(MockHttpResponse response) {
        return ResponseEntity.status(response.httpStatus())
                .contentType(MediaType.valueOf(response.contentType()))
                .body(response.body());
    }
}
