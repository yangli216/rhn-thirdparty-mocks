package com.rhn.mock.core.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 兜底异常处理：避免未预期异常以 HTML 错误页返回，便于联调定位。 */
@RestControllerAdvice
public class MockApiExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handle(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        String body = "{\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return ResponseEntity.status(500)
                .contentType(MediaType.valueOf("application/json;charset=UTF-8"))
                .body(body);
    }
}
