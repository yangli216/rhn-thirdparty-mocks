package com.rhn.mock.protocol.ca;

/** CA 业务失败：HTTP 200 + {@code code=-1} + 字符串 {@code errorCode}（错误码表见接口文档）。 */
public class CaFailure extends RuntimeException {
    private final String errorCode;

    public CaFailure(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
