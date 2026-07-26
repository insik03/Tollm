package com.tollm.global.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollm.domain.apikey.ApiKeyLookupService;
import com.tollm.global.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

// /v1/** 프록시 요청 전용 인증 필터 (JWT가 아니라 발급 키 tlm_xxx로 인증)
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    // JwtAuthFilter와 동일한 이유: getRequestURI(미디코딩)로 /v1 여부를 판정하면
    // /%761/chat 같은 인코딩 우회로 필터를 건너뛸 수 있다. Spring 매핑과 같은 디코딩 경로로 판정한다.
    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;

    private final ApiKeyLookupService apiKeyLookupService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH_HELPER.getPathWithinApplication(request).startsWith("/v1");
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
        // 같은 키는 항상 같은 해시 → 유니크 인덱스로 한 번에 조회 가능 (발표 포인트).
        // 조회 자체는 ApiKeyLookupService가 짧은 TTL 캐시로 감싸 매 요청 DB 왕복을 줄인다.
        String keyHash = HashUtils.sha256(rawKey);

        var principal = apiKeyLookupService.authenticate(keyHash).orElse(null);
        if (principal == null) {
            writeError(response, "유효하지 않거나 폐기된 API 키입니다");
            return;
        }
        request.setAttribute("userId", principal.userId());
        // 팀 키일 때만 채워짐(add-on) - 개인 키는 기존과 동일하게 null
        request.setAttribute("teamId", principal.teamId());
        // 어떤 "키"로 왔는지 - 같은 사용자가 키를 여러 개 발급받아도 요청별로
        // 어느 키를 썼는지 구분하려면 userId만으론 부족해서 별도로 싣는다
        request.setAttribute("apiKeyId", principal.apiKeyId());
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                new ErrorResponse("UNAUTHORIZED", message)));
    }
}
