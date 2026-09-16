package com.monitor.sdk.agent;

import com.monitor.sdk.AsyncHttpEventReporter;
import com.monitor.sdk.EventReporter;
import com.monitor.sdk.MonitorEvent;
import com.monitor.sdk.ReporterConfig;
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
        defaultBizLine = config.getDefaultBizLine();
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
            MonitorEvent.Builder event = MonitorEvent.builder(spec.eventType())
                    .orderId(asRequiredText(argument(arguments, spec.orderIdArg()), "orderIdArg"))
                    .bizLine(firstText(spec.bizLine(), defaultBizLine, "bizLine"))
                    .cityId(asLong(argument(arguments, spec.cityIdArg()), "cityIdArg"));

            Object tripId = argument(arguments, spec.tripIdArg());
            if (tripId != null) {
                event.tripId(String.valueOf(tripId));
            }
            Object seatType = argument(arguments, spec.seatTypeArg());
            if (seatType != null) {
                event.seatType(String.valueOf(seatType));
            }
            Object driverId = argument(arguments, spec.driverIdArg());
            if (driverId != null) {
                event.hashedDriverId(String.valueOf(driverId));
            }
            Object amount = argument(arguments, spec.amountFenArg());
            if (amount != null) {
                event.amountFen(asLong(amount, "amountFenArg"));
            }
            Object eventTime = argument(arguments, spec.eventTimeArg());
            if (eventTime != null) {
                event.eventTime(asInstant(eventTime, "eventTimeArg"));
            }
            String[] propNames = spec.propNames();
            int[] propIndexes = spec.propArgIndexes();
            if (propNames.length != propIndexes.length) {
                throw new IllegalArgumentException("propNames and propArgIndexes must have the same length");
            }
            for (int i = 0; i < propNames.length; i++) {
                event.prop(propNames[i], argument(arguments, propIndexes[i]));
            }
            active.report(event.build());
        } catch (Throwable ignored) {
            // Agent 是旁路能力：任何映射、序列化和队列错误均不得改变业务调用结果。
        }
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
        return String.valueOf(value);
    }

    private static String firstText(String preferred, String fallback, String name) {
        if (preferred != null && !preferred.trim().isEmpty()) {
            return preferred;
        }
        if (fallback != null && !fallback.trim().isEmpty()) {
            return fallback;
        }
        throw new IllegalArgumentException(name + " is required either on annotation or agent config");
    }

    private static long asLong(Object value, String name) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
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
            return Instant.parse(value.toString());
        }
        throw new IllegalArgumentException(name + " must be Instant, OffsetDateTime, Date, epoch millis, or ISO-8601 string");
    }
}
