package com.tollm.global.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollm.global.config.TollmProperties;
import com.tollm.global.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// [보안 수정 SEC-02] /v1/** (프록시 요청) 본문 크기 제한.
// ApiKeyAuthFilter/JwtAuthFilter와 같은 "필터" 계층 컴포넌트라 여기(global.auth)에 함께 둔다
// (인증 자체는 아니지만, 이 프로젝트는 필터를 이 패키지에 모아두는 기존 관례를 따른다).
//
// Tomcat의 maxPostSize/max-http-form-post-size는 application/x-www-form-urlencoded나
// multipart 파라미터 파싱에만 적용되고, ProxyController처럼 @RequestBody String으로
// 원문 그대로 읽는 요청에는 적용되지 않는다. 그래서 Content-Length 헤더를 직접 검사하는
// 필터로 본문을 실제로 읽기 전에 차단한다.
//
// [한계] Content-Length 헤더가 없는 요청(예: chunked transfer-encoding)은 이 검사를
// 통과한다 - 이 프로젝트의 클라이언트(REST 호출)는 통상 Content-Length를 보내므로 실무적으로는
// 충분하다고 판단했지만, 완전한 방어(스트림을 직접 제한 바이트만큼만 읽고 초과 시 끊는 방식)는
// 아니다. 스코프를 넘는 리팩토링(Tomcat Connector 커스터마이징 등)은 하지 않고 이 한계를 문서로
// 남긴다 (docs/progress-week2.md, 01-backend-design.md 참고).
@Component
@RequiredArgsConstructor
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final String PROXY_PREFIX = "/v1";

    private final TollmProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PROXY_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        long limit = properties.getProxy().getMaxRequestBodyBytes();

        if (contentLength > limit) {
            writeError(response, limit);
            return; // filterChain.doFilter를 호출하지 않으면 본문을 읽지 않고 여기서 끝난다
        }
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, long limit) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE); // 413
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                new ErrorResponse("PAYLOAD_TOO_LARGE", "요청 본문이 너무 큽니다 (최대 " + limit + " bytes)")));
    }
}
