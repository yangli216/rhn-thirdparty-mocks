package com.rhn.mock.core;

/** 需要直接返回 HTTP 层异常的场景（如 TEST-HTTP500 / TEST-BADJSON），不走业务回执。 */
public class MockRawResponse extends RuntimeException {
    private final int status;
    private final String body;
    private final String contentType;

    public MockRawResponse(int status, String body, String contentType) {
        super("raw response " + status);
        this.status = status;
        this.body = body;
        this.contentType = contentType;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }

    public String contentType() {
        return contentType;
    }
}
