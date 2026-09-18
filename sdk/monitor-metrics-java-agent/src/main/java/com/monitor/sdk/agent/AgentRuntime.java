package com.monitor.sdk.agent;

import com.monitor.sdk.AsyncHttpEventReporter;
import com.monitor.sdk.EventReporter;
import com.monitor.sdk.MonitorEvent;
import com.monitor.sdk.annotation.MonitorMetricEvent;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Date;

/** Advice 与 reporter 之间的运行时桥接；所有错误均被隔离，绝不影响被织入的业务方法。 */
public final class AgentRuntime {

    private static volatile EventReporter reporter;
    private static volatile String defaultBizLine;

    private AgentRuntime() {
    }

    static void initialize(AgentConfig config) {
        reporter = new AsyncHttpEventReporter(config.getReporterConfig());
        defaultBizLine = config.getBizLine();
    }

    static void close() {
        EventReporter active = reporter;
        if (active != null) {
            active.close();
        }
    }

    /** 由 Byte Buddy Advice 调用。此方法必须不向被代理方法抛出任何异常。 */
    public static void report(Method method, Object[] arguments, Throwable error) {
        try {
            MonitorMetricEvent spec = method.getAnnotation(MonitorMetricEvent.class);
            if (spec == null || (error != null && !spec.reportOnThrowable())) {
                return;
            }
            EventReporter active = reporter;
            if (active == null) {
                return;
            }

            // bizLine: 优先使用注解标注值，缺省时自动使用项目中配置文件设置的全局 bizLine
            String bizLine = firstText(spec.bizLine(), defaultBizLine, "bizLine");

            // orderId: 优先解析 orderIdPath，其次 orderIdArg
            Object orderId = resolveValue(arguments, method, spec.orderIdPath(), spec.orderIdArg());

            // cityId: 优先解析 cityIdPath，其次 cityIdArg
            Object cityId = resolveValue(arguments, method, spec.cityIdPath(), spec.cityIdArg());

            MonitorEvent.Builder event = MonitorEvent.builder(spec.eventType())
                    .orderId(asRequiredText(orderId, "orderId"))
                    .bizLine(bizLine)
                    .cityId(asLong(cityId, "cityId"));

            Object tripId = resolveValue(arguments, method, spec.tripIdPath(), spec.tripIdArg());
            if (tripId != null) {
                event.tripId(String.valueOf(tripId));
            }

            Object seatType = resolveValue(arguments, method, spec.seatTypePath(), spec.seatTypeArg());
            if (seatType != null) {
                event.seatType(String.valueOf(seatType));
            }

            Object driverId = resolveValue(arguments, method, spec.driverIdPath(), spec.driverIdArg());
            if (driverId != null) {
                event.hashedDriverId(String.valueOf(driverId));
            }

            Object amount = resolveValue(arguments, method, spec.amountFenPath(), spec.amountFenArg());
            if (amount != null) {
                event.amountFen(asLong(amount, "amountFen"));
            }

            Object eventTime = resolveValue(arguments, method, spec.eventTimePath(), spec.eventTimeArg());
            if (eventTime != null) {
                event.eventTime(asInstant(eventTime, "eventTime"));
            }

            String[] propNames = spec.propNames();
            String[] propPaths = spec.propPaths();
            int[] propIndexes = spec.propArgIndexes();

            if (propPaths != null && propPaths.length > 0) {
                if (propNames.length != propPaths.length) {
                    throw new IllegalArgumentException("propNames and propPaths must have the same length");
                }
                for (int i = 0; i < propNames.length; i++) {
                    int fallbackIdx = (propIndexes != null && i < propIndexes.length) ? propIndexes[i] : -1;
                    Object val = resolveValue(arguments, method, propPaths[i], fallbackIdx);
                    if (val != null) {
                        event.prop(propNames[i], val);
                    }
                }
            } else if (propIndexes != null && propIndexes.length > 0) {
                if (propNames.length != propIndexes.length) {
                    throw new IllegalArgumentException("propNames and propArgIndexes must have the same length");
                }
                for (int i = 0; i < propNames.length; i++) {
                    Object val = argument(arguments, propIndexes[i]);
                    if (val != null) {
                        event.prop(propNames[i], val);
                    }
                }
            }

            active.report(event.build());
        } catch (Throwable ignored) {
            // Agent 是旁路能力：任何映射、序列化和队列错误均不得改变业务调用结果。
        }
    }

    private static Object resolveValue(Object[] arguments, Method method, String path, int fallbackArgIndex) {
        if (path != null && !path.trim().isEmpty()) {
            return PropertyExtractor.extract(arguments, method, path);
        }
        if (fallbackArgIndex >= 0) {
            return argument(arguments, fallbackArgIndex);
        }
        return null;
    }

    private static Object argument(Object[] arguments, int index) {
        if (index < 0) {
            return null;
        }
        if (arguments == null || index >= arguments.length) {
            throw new IllegalArgumentException("argument index " + index + " is outside the method argument list");
        }
        return arguments[index];
    }

    private static String asRequiredText(Object value, String name) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must resolve to a non-blank value");
        }
        return String.valueOf(value).trim();
    }

    private static String firstText(String preferred, String fallback, String name) {
        if (preferred != null && !preferred.trim().isEmpty()) {
            return preferred.trim();
        }
        if (fallback != null && !fallback.trim().isEmpty()) {
            return fallback.trim();
        }
        throw new IllegalArgumentException(name + " is required either on annotation or agent config");
    }

    private static long asLong(Object value, String name) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // fall through to the explicit contract error below
            }
        }
        throw new IllegalArgumentException(name + " must resolve to a number");
    }

    private static Instant asInstant(Object value, String name) {
        if (value instanceof Instant) {
            return (Instant) value;
        }
        if (value instanceof OffsetDateTime) {
            return ((OffsetDateTime) value).toInstant();
        }
        if (value instanceof ZonedDateTime) {
            return ((ZonedDateTime) value).toInstant();
        }
        if (value instanceof Date) {
            return ((Date) value).toInstant();
        }
        if (value instanceof Number) {
            return Instant.ofEpochMilli(((Number) value).longValue());
        }
        if (value instanceof CharSequence) {
            return Instant.parse(value.toString().trim());
        }
        throw new IllegalArgumentException(name + " must be Instant, OffsetDateTime, Date, epoch millis, or ISO-8601 string");
    }
}
