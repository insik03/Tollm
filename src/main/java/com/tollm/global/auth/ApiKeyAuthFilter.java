package com.tollm.global.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollm.domain.apikey.ApiKey;
import com.tollm.domain.apikey.ApiKeyRepository;
import com.tollm.global.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// /v1/** 프록시 요청 전용 인증 필터 (JWT가 아니라 발급 키 tlm_xxx로 인증)
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyRepository apiKeyRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeError(response, "API 키가 필요합니다 (Authorization: Bearer tlm_...)");
            return;
        }

        String rawKey = header.substring(7);
        // 원문이 아니라 해시로 조회: DB가 유출돼도 키 원문은 없다. SHA-256은 결정적이라
        // 같은 키는 항상 같은 해시 → 유니크 인덱스로 한 번에 조회 가능 (발표 포인트)
        String keyHash = HashUtils.sha256(rawKey);

        apiKeyRepository.findByKeyHashAndStatus(keyHash, ApiKey.Status.ACTIVE)
                .ifPresentOrElse(
                        key -> request.setAttribute("userId", key.getUser().getId()),
                        () -> request.setAttribute("userId", null)
                );

        if (request.getAttribute("userId") == null) {
            writeError(response, "유효하지 않거나 폐기된 API 키입니다");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                new ErrorResponse("UNAUTHORIZED", message)));
    }
}
