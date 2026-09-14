package com.minitor.server.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLException;
import java.util.stream.Collectors;

/**
 * 全局异常 → 统一响应外壳。HTTP 状态码由 {@link ErrorCode} 决定。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Object>> handleApi(ApiException ex) {
        ErrorCode code = ex.errorCode();
        if (code.status().is5xxServerError()) {
            log.error("[{}] {} : {}", RequestContext.requestId(), code.label(), ex.getMessage(), ex);
        } else {
            log.warn("[{}] {} : {}", RequestContext.requestId(), code.label(), ex.getMessage());
        }
        return ResponseEntity.status(code.status())
                .body(ApiResponse.error(code, ex.getMessage(), ex.detail()));
    }

    /** @Valid 请求体校验失败 → 40001，逐字段返回原因。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(ErrorCode.INVALID_PARAM.status())
                .body(ApiResponse.error(ErrorCode.INVALID_PARAM, detail));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiResponse<Object>> handleBadRequest(Exception ex) {
        return ResponseEntity.status(ErrorCode.INVALID_PARAM.status())
                .body(ApiResponse.error(ErrorCode.INVALID_PARAM, ex.getMessage()));
    }

    /**
     * 存储不可用 → 50301。链路失明本身由 P0 规则 #4 告警，
     * 前端据此展示「数据失明」横幅而非把断流误读为业务正常。
     */
    @ExceptionHandler({SQLException.class, org.springframework.dao.DataAccessException.class})
    public ResponseEntity<ApiResponse<Object>> handleStore(Exception ex) {
        log.error("[{}] store unavailable", RequestContext.requestId(), ex);
        return ResponseEntity.status(ErrorCode.STORE_UNAVAILABLE.status())
                .body(ApiResponse.error(ErrorCode.STORE_UNAVAILABLE,
                        "查询存储暂不可用，请稍后重试或查看元监控"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleOther(Exception ex) {
        log.error("[{}] unhandled", RequestContext.requestId(), ex);
        return ResponseEntity.status(ErrorCode.INTERNAL.status())
                .body(ApiResponse.error(ErrorCode.INTERNAL, ex.getMessage()));
    }
}
