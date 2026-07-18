package com.tollm.global.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollm.global.error.ErrorResponse;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// /keys, /usage, /teams, /admin 등 관리용 API의 JWT 인증 필터
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final List<String> PROTECTED_PREFIXES =
            List.of("/keys", "/usage", "/teams", "/admin");

    private final JwtProvider jwtProvider;
    private final ObjectMapper objectMapper;

    // true를 반환하면 이 요청은 필터를 건너뛴다 (/auth, /v1 등은 JWT 검증 대상 아님)
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PROTECTED_PREFIXES.stream().noneMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "인증 토큰이 필요합니다");
            return; // filterChain.doFilter를 호출하지 않으면 요청은 여기서 끝난다
        }

        try {
            String token = header.substring(7); // "Bearer " 이후가 실제 토큰
            Long userId = jwtProvider.getUserId(token);
            String role = jwtProvider.parse(token).get("role", String.class);

            // /admin/** 은 ADMIN만
            if (request.getRequestURI().startsWith("/admin") && !"ADMIN".equals(role)) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "관리자 권한이 필요합니다");
                return;
            }

            // 컨트롤러가 @RequestAttribute로 꺼내 쓸 수 있게 저장
            request.setAttribute("userId", userId);
            request.setAttribute("role", role);
            filterChain.doFilter(request, response); // 통과 → 다음 단계(컨트롤러)로
        } catch (JwtException | IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "유효하지 않은 토큰입니다");
        }
    }

    // 필터는 컨트롤러 밖이라 GlobalExceptionHandler가 못 잡는다 → 직접 JSON 응답을 쓴다
    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                new ErrorResponse(status == 403 ? "FORBIDDEN" : "UNAUTHORIZED", message)));
    }
}
