package com.monitor.server.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 统一响应外壳（接口文档 §1.1）。
 *
 * <p>{@code code} 为业务码，HTTP 状态码只表达传输语义；前端按 code 分支，不解析 message 文案。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        int code,
        String message,
        String requestId,
        OffsetDateTime serverTime,
        T data
) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(
                ErrorCode.OK.code(),
                "OK",
                RequestContext.requestId(),
                OffsetDateTime.now(ZONE),
                data);
    }

    public static <T> ApiResponse<T> error(ErrorCode code, String message) {
        return new ApiResponse<>(
                code.code(),
                message == null ? code.label() : message,
                RequestContext.requestId(),
                OffsetDateTime.now(ZONE),
                null);
    }

    public static <T> ApiResponse<T> error(ErrorCode code, String message, T detail) {
        return new ApiResponse<>(
                code.code(),
                message == null ? code.label() : message,
                RequestContext.requestId(),
                OffsetDateTime.now(ZONE),
                detail);
    }
}
