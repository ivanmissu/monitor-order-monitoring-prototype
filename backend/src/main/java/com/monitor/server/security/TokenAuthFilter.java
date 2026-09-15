package com.monitor.server.security;

import com.monitor.server.common.ApiException;
import com.monitor.server.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Bearer token 鉴权 + 行策略装载。
 *
 * <p>演示实现用静态 token→角色映射；生产由 SSO 换取 15min 短时效用户态 token，
 * 身份与角色绑定后写审计日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TokenAuthFilter extends OncePerRequestFilter {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();
    private final TokenProperties properties;

    public TokenAuthFilter(TokenProperties properties) {
        this.properties = properties;
    }

    /**
     * 调用方身份。{@code cityPolicy} 为空集表示不限城市（全国）。
     */
    public record Principal(String subject, TokenRole role, Set<Long> cityPolicy, Set<String> bizPolicy) {

        public boolean allCities() {
            return cityPolicy == null || cityPolicy.isEmpty();
        }

        /** 行策略校验：越权直接 40301，不做静默过滤。 */
        public void assertCity(Long cityId) {
            if (cityId == null || cityId == 0L || allCities()) {
                return;
            }
            if (!cityPolicy.contains(cityId)) {
                throw ApiException.forbiddenDimension("city_id", cityId);
            }
        }

        public void assertScope(String scope) {
            if (!role.can(scope)) {
                throw new ApiException(ErrorCode.FORBIDDEN_DIMENSION,
                        "当前角色 " + role + " 无 " + scope + " 访问权");
            }
        }
    }

    public static Principal current() {
        Principal p = CURRENT.get();
        if (p == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "缺少或无效的 Authorization token");
        }
        return p;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/") || path.startsWith("/actuator") || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            TokenRole role = properties.resolve(token);
            if (role != null) {
                CURRENT.set(new Principal(
                        properties.subjectOf(token),
                        role,
                        properties.cityPolicyOf(token),
                        Collections.emptySet()));
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            CURRENT.remove();
        }
    }

    /**
     * token → 角色 / 行策略配置。
     */
    @Component
    @ConfigurationProperties(prefix = "monitor.security")
    public static class TokenProperties {

        private Map<String, String> tokens = new LinkedHashMap<>();
        private Map<String, Set<Long>> cityPolicies = new LinkedHashMap<>();

        public Map<String, String> getTokens() {
            return tokens;
        }

        public void setTokens(Map<String, String> tokens) {
            this.tokens = tokens;
        }

        public Map<String, Set<Long>> getCityPolicies() {
            return cityPolicies;
        }

        public void setCityPolicies(Map<String, Set<Long>> cityPolicies) {
            this.cityPolicies = cityPolicies;
        }

        TokenRole resolve(String token) {
            String role = tokens.get(token);
            if (role == null) {
                return null;
            }
            try {
                return TokenRole.valueOf(role);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        String subjectOf(String token) {
            TokenRole role = resolve(token);
            return role == null ? "anonymous" : role.account();
        }

        Set<Long> cityPolicyOf(String token) {
            return cityPolicies.getOrDefault(token, Collections.emptySet());
        }
    }
}
