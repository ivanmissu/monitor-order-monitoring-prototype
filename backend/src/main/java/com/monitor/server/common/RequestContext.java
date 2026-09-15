package com.monitor.server.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 请求级上下文 + 必带响应头（接口文档 §1.2）。
 *
 * <p>查询链路在执行过程中调用 {@link #freshness(Instant)}、{@link #partial(boolean)}、
 * {@link #grain(String)} 汇报口径元信息，Filter 在响应提交前统一写入响应头，
 * 使得任意一个端点都自动携带口径水印，无需各 Controller 重复处理。
 */
public final class RequestContext {

    public static final String H_REQUEST_ID = "X-Request-Id";
    public static final String H_FRESHNESS = "X-Monitor-Freshness";
    public static final String H_PARTIAL = "X-Monitor-partial";
    public static final String H_GRAIN = "X-Monitor-Grain";
    public static final String H_DICT_VERSION = "X-Monitor-Dict-Version";
    public static final String H_IDEMPOTENT_REPLAY = "X-Idempotent-Replay";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ThreadLocal<Holder> HOLDER = new ThreadLocal<>();

    private RequestContext() {
    }

    /** 单请求可变状态。多个子查询汇报时取「最旧的新鲜度」与「任一 partial 即 partial」。 */
    public static final class Holder {
        private final String requestId;
        private Instant freshness;
        private boolean partial;
        private String grain;

        Holder(String requestId) {
            this.requestId = requestId;
        }
    }

    static void begin(String requestId) {
        HOLDER.set(new Holder(requestId));
    }

    static void end() {
        HOLDER.remove();
    }

    private static Holder holder() {
        return HOLDER.get();
    }

    public static String requestId() {
        Holder h = holder();
        return h == null ? null : h.requestId;
    }

    /** 汇报本次结果覆盖的最新事件时间；多次汇报保留最保守（最早）的一个。 */
    public static void freshness(Instant instant) {
        Holder h = holder();
        if (h == null || instant == null) {
            return;
        }
        if (h.freshness == null || instant.isBefore(h.freshness)) {
            h.freshness = instant;
        }
    }

    /** 标记结果未定盘（当天值仍会因迟到事件 / T+1 回补变化）。 */
    public static void partial(boolean partial) {
        Holder h = holder();
        if (h != null && partial) {
            h.partial = true;
        }
    }

    /** 汇报实际生效粒度（小样本降级后可能与请求粒度不同）。 */
    public static void grain(String grain) {
        Holder h = holder();
        if (h != null && grain != null) {
            h.grain = grain;
        }
    }

    /**
     * 写入请求 ID 与口径水印响应头。顺序最高，保证异常响应同样带头。
     */
    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class ContextFilter extends OncePerRequestFilter {

        private final String dictVersion;

        public ContextFilter(org.springframework.core.env.Environment env) {
            this.dictVersion = env.getProperty("monitor.dict-version", "2026.09");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String incoming = request.getHeader(H_REQUEST_ID);
            String requestId = (incoming == null || incoming.isBlank())
                    ? UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                    : incoming;
            begin(requestId);
            try {
                response.setHeader(H_REQUEST_ID, requestId);
                response.setHeader(H_DICT_VERSION, dictVersion);
                // 响应提交前补齐口径水印
                response.addHeader("Vary", "Authorization");
                chain.doFilter(request, response);
            } finally {
                Holder h = holder();
                if (h != null && !response.isCommitted()) {
                    if (h.freshness != null) {
                        response.setHeader(H_FRESHNESS,
                                OffsetDateTime.ofInstant(h.freshness, ZONE).toString());
                    }
                    if (h.partial) {
                        response.setHeader(H_PARTIAL, "true");
                    }
                    if (h.grain != null) {
                        response.setHeader(H_GRAIN, h.grain);
                    }
                }
                end();
            }
        }
    }
}
