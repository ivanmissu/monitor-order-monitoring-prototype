package com.monitor.sdk.internal;

import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;

/** 无第三方依赖的最小 JSON 编码器，仅用于 SDK 向固定 HTTP 契约写出请求体。 */
public final class JsonCodec {

    private JsonCodec() {
    }

    public static String toJson(Object value) {
        StringBuilder out = new StringBuilder(256);
        append(out, value);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof CharSequence || value instanceof Character || value instanceof Enum<?>) {
            quote(out, String.valueOf(value));
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Number) {
            Number number = (Number) value;
            if ((number instanceof Double && !Double.isFinite(number.doubleValue()))
                    || (number instanceof Float && !Float.isFinite(number.floatValue()))) {
                throw new IllegalArgumentException("JSON does not support NaN or Infinity");
            }
            out.append(number);
        } else if (value instanceof TemporalAccessor) {
            quote(out, value.toString());
        } else if (value instanceof Date) {
            quote(out, Long.toString(((Date) value).getTime()));
        } else if (value instanceof Map<?, ?>) {
            out.append('{');
            Iterator<? extends Map.Entry<?, ?>> iterator = ((Map<?, ?>) value).entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<?, ?> entry = iterator.next();
                quote(out, String.valueOf(entry.getKey()));
                out.append(':');
                append(out, entry.getValue());
                if (iterator.hasNext()) {
                    out.append(',');
                }
            }
            out.append('}');
        } else if (value instanceof Iterable<?>) {
            out.append('[');
            Iterator<?> iterator = ((Iterable<?>) value).iterator();
            while (iterator.hasNext()) {
                append(out, iterator.next());
                if (iterator.hasNext()) {
                    out.append(',');
                }
            }
            out.append(']');
        } else if (value.getClass().isArray()) {
            out.append('[');
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                append(out, Array.get(value, i));
                if (i + 1 < length) {
                    out.append(',');
                }
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported props value type: " + value.getClass().getName());
        }
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
