package com.minitor.server.domain;

import com.minitor.server.common.ApiException;

import java.time.Duration;
import java.util.Arrays;

/**
 * 聚合粒度 ↔ 物理表映射（设计文档 §4.5 聚合分层）。
 *
 * <p>{@code H2} 是大盘展示粒度，没有物理表，由 {@code agg_1h} 上卷得到。
 */
public enum Grain {

    M1("1m", "agg_1m", 60, true),
    M5("5m", "agg_5m", 300, true),
    H1("1h", "agg_1h", 3600, false),
    H2("2h", "agg_1h", 7200, false),
    D1("1d", "agg_1d", 86400, false);

    private final String id;
    private final String table;
    private final int seconds;
    private final boolean minuteLevel;

    Grain(String id, String table, int seconds, boolean minuteLevel) {
        this.id = id;
        this.table = table;
        this.seconds = seconds;
        this.minuteLevel = minuteLevel;
    }

    public String id() {
        return id;
    }

    /** 物理聚合表名。 */
    public String table() {
        return table;
    }

    public int seconds() {
        return seconds;
    }

    public boolean minuteLevel() {
        return minuteLevel;
    }

    /** 时间列名：分钟表为 minute，小时/日表为 hour / dt。 */
    public String timeColumn() {
        return switch (this) {
            case M1, M5 -> "minute";
            case H1, H2 -> "hour";
            case D1 -> "dt";
        };
    }

    public static Grain of(String raw) {
        if (raw == null || raw.isBlank()) {
            return H1;
        }
        return Arrays.stream(values())
                .filter(g -> g.id.equalsIgnoreCase(raw))
                .findFirst()
                .orElseThrow(() -> ApiException.invalidParam("未知 grain: " + raw));
    }

    /**
     * 粒度收敛：跨度过大时禁止分钟粒度，服务端自动降级（避免扫描爆炸）。
     *
     * @param span            查询跨度
     * @param minuteMaxDays   允许使用分钟粒度的最大跨度天数
     */
    public Grain coerce(Duration span, int minuteMaxDays) {
        if (!minuteLevel) {
            return this;
        }
        if (span.toDays() > minuteMaxDays) {
            return span.toDays() > 31 ? D1 : H1;
        }
        return this;
    }
}
