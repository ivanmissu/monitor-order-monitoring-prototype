package com.minitor.server.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码（接口文档 §02）。前端按 code 分支处理，禁止解析 message 文案。
 */
public enum ErrorCode {

    OK(0, HttpStatus.OK, "OK"),

    /** 参数缺失 / 枚举非法 / from>to / 维度未注册 */
    INVALID_PARAM(40001, HttpStatus.BAD_REQUEST, "INVALID_PARAM"),
    /** 明细查询时间窗 > 90 天，或告警窗口超过保留期 */
    WINDOW_TOO_LARGE(40002, HttpStatus.BAD_REQUEST, "WINDOW_TOO_LARGE"),
    /** 维度 × 分钟样本 < 20 且未开启降级 */
    SAMPLE_TOO_SMALL(40003, HttpStatus.BAD_REQUEST, "SAMPLE_TOO_SMALL"),

    UNAUTHENTICATED(40101, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"),
    /** city_id / biz_line 超出账号行策略：直接报错，不静默过滤 */
    FORBIDDEN_DIMENSION(40301, HttpStatus.FORBIDDEN, "FORBIDDEN_DIMENSION"),
    NOT_FOUND(40401, HttpStatus.NOT_FOUND, "NOT_FOUND"),
    /** 告警已被他人认领 / 字典版本乐观锁冲突 */
    ALREADY_CLAIMED(40901, HttpStatus.CONFLICT, "ALREADY_CLAIMED"),

    BATCH_TOO_LARGE(41301, HttpStatus.PAYLOAD_TOO_LARGE, "BATCH_TOO_LARGE"),
    /** event_type 未登记 / 必填维度为空 / props 命中 PII 规则 */
    EVENT_SCHEMA_INVALID(42201, HttpStatus.UNPROCESSABLE_ENTITY, "EVENT_SCHEMA_INVALID"),
    RATE_LIMITED(42901, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED"),

    INTERNAL(50001, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL"),
    /** ClickHouse 两副本均不可查 —— 链路失明本身是 P0 告警 */
    STORE_UNAVAILABLE(50301, HttpStatus.SERVICE_UNAVAILABLE, "STORE_UNAVAILABLE"),
    /** T+1 回补进行中，该分区暂不可对外 */
    BACKFILL_RUNNING(50302, HttpStatus.SERVICE_UNAVAILABLE, "BACKFILL_RUNNING");

    private final int code;
    private final HttpStatus status;
    private final String label;

    ErrorCode(int code, HttpStatus status, String label) {
        this.code = code;
        this.status = status;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String label() {
        return label;
    }
}
