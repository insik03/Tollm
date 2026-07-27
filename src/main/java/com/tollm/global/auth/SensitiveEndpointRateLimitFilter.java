package com.tollm.global.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollm.domain.proxy.RateLimitService;
import com.tollm.global.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

// [봇 방어] 인증 전(가입/로그인)·키 발급처럼 남용에 취약한 엔드포인트를 IP 단위로 레이트리밋한다.
// /v1 프록시에만 있던 레이트리밋을 이 관리 엔드포인트에도 확장해, 봇이 계정을 대량으로 만들거나
// 키를 무제한 발급해 DB/CPU를 소모(응용계층 DoS)하는 것을 막는다.
// 되도록 다른 필터(인증)보다 먼저 돌게 해, 차단된 요청은 인증 처리조차 하지 않고 빠르게 429로 끝낸다.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class SensitiveEndpointRateLimitFilter extends OncePerRequestFilter {

    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = PATH_HELPER.getPathWithinApplication(request);
        boolean sensitive = path.equals("/auth/signup") || path.equals("/auth/login")
                || path.equals("/keys")
                || (path.startsWith("/teams/") && path.endsWith("/keys")); // 팀 키 발급
        return !sensitive;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!rateLimitService.tryConsumeByIp(clientIp(request))) {
            response.setStatus(429); // 429 Too Many Requests (서블릿 API에 상수가 없어 리터럴 사용)
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    new ErrorResponse("TOO_MANY_REQUESTS", "요청이 너무 많습니다. 잠시 후 다시 시도하세요")));
            return;
        }
        filterChain.doFilter(request, response);
    }

    // Nginx 뒤에서는 request.getRemoteAddr()가 항상 Nginx(127.0.0.1)라 IP 구분이 안 된다.
    // Nginx가 넣어주는 X-Forwarded-For의 첫 항목(원 클라이언트 IP)을 우선 사용한다.
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
