package com.monitor.server.domain;

import com.monitor.server.common.ApiException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 查询时间窗。业务归属日按 Asia/Shanghai 日切，全系统唯一归属日规则（设计文档 §4.4）。
 */
public record TimeRange(LocalDate from, LocalDate to, Grain grain) {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public TimeRange {
        if (from == null || to == null) {
            throw ApiException.invalidParam("from / to 不能为空");
        }
        if (from.isAfter(to)) {
            throw ApiException.invalidParam("from 不能晚于 to");
        }
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /**
     * 解析并收敛粒度。
     *
     * @param minuteMaxDays 分钟粒度允许的最大跨度（超过则自动降级为 1h/1d）
     */
    public static TimeRange parse(String from, String to, String grain, int minuteMaxDays) {
        LocalDate f = parseDate(from, today());
        LocalDate t = parseDate(to, today());
        Grain g = Grain.of(grain);
        Duration span = Duration.ofDays(f.until(t).getDays() + 1L);
        return new TimeRange(f, t, g.coerce(span, minuteMaxDays));
    }

    private static LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw);
        } catch (DateTimeParseException ex) {
            throw ApiException.invalidParam("日期格式非法: " + raw + "（期望 yyyy-MM-dd）");
        }
    }

    public long days() {
        return from.until(to).getDays() + 1L;
    }

    /** 是否包含今天 —— 包含则结果未定盘，需打 partial 标记。 */
    public boolean includesToday() {
        LocalDate today = today();
        return !from.isAfter(today) && !to.isBefore(today);
    }

    /** 强制时间窗校验（明细查询最长 90 天）。 */
    public TimeRange assertWithin(int maxDays) {
        if (days() > maxDays) {
            throw ApiException.windowTooLarge(maxDays);
        }
        return this;
    }

    public TimeRange withGrain(Grain g) {
        return new TimeRange(from, to, g);
    }
}
