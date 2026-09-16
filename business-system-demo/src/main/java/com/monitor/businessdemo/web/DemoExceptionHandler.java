package com.monitor.businessdemo.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/** 让 curl 联调时直接看到可读错误，而不是 Spring 默认 HTML 错误页。 */
@RestControllerAdvice
public class DemoExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorBody badRequest(IllegalArgumentException ex) {
        return new ErrorBody("BAD_REQUEST", ex.getMessage(), Instant.now());
    }

    public record ErrorBody(String code, String message, Instant timestamp) {
    }
}
