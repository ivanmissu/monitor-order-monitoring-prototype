package com.monitor.server.common;

/**
 * 业务异常。所有对外错误统一走此类型，由 {@link GlobalExceptionHandler} 转成响应外壳。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object detail;

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public ApiException(ErrorCode errorCode, String message, Object detail) {
        super(message == null ? errorCode.label() : message);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Object detail() {
        return detail;
    }

    // ── 便捷工厂 ──────────────────────────────────────────────────────────

    public static ApiException invalidParam(String message) {
        return new ApiException(ErrorCode.INVALID_PARAM, message);
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " 不存在");
    }

    public static ApiException windowTooLarge(int maxDays) {
        return new ApiException(ErrorCode.WINDOW_TOO_LARGE,
                "查询时间窗超过上限 " + maxDays + " 天，请缩小范围");
    }

    /** 越权维度：明确报错而非静默过滤，避免前端把「无权限」误读为「数据为 0」。 */
    public static ApiException forbiddenDimension(String dim, Object value) {
        return new ApiException(ErrorCode.FORBIDDEN_DIMENSION,
                "维度 " + dim + "=" + value + " 超出当前账号行策略");
    }

    public static ApiException schemaInvalid(String message, Object detail) {
        return new ApiException(ErrorCode.EVENT_SCHEMA_INVALID, message, detail);
    }
}
