package com.rhn.mock.core;

/** 一次业务调用的 HTTP 应答（状态、正文、内容类型）。 */
public record MockHttpResponse(int httpStatus, String body, String contentType) {
    public static MockHttpResponse json(String body) {
        return new MockHttpResponse(200, body, "application/json;charset=UTF-8");
    }

    public static MockHttpResponse raw(int status, String body, String contentType) {
        return new MockHttpResponse(status, body, contentType);
    }
}
