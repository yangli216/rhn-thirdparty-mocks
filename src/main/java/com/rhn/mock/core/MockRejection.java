package com.rhn.mock.core;

/** 业务拒绝：对应 HTTP 200 的业务失败回执（各协议自行决定失败表达方式）。 */
public class MockRejection extends RuntimeException {
    public MockRejection(String message) {
        super(message);
    }
}
