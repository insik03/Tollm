package com.tollm.global.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollm.global.config.TollmProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RequestSizeLimitFilterTest {

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    private RequestSizeLimitFilter filter;

    @BeforeEach
    void setUp() {
        TollmProperties properties = new TollmProperties();
        properties.getProxy().setMaxRequestBodyBytes(1_000_000); // 1MB
        filter = new RequestSizeLimitFilter(properties, new ObjectMapper());
    }

    @Test
    void v1로_시작하지_않는_경로는_검사하지_않는다() {
        given(request.getRequestURI()).willReturn("/usage/me");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void 제한_이하_본문은_통과한다() throws Exception {
        given(request.getContentLengthLong()).willReturn(500_000L);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    }

    @Test
    void 제한을_초과한_본문은_413으로_거부하고_체인을_호출하지_않는다() throws Exception {
        given(request.getContentLengthLong()).willReturn(2_000_000L);
        StringWriter sw = new StringWriter();
        given(response.getWriter()).willReturn(new PrintWriter(sw));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        verify(filterChain, never()).doFilter(request, response);
        assertThat(sw.toString()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void Content_Length가_없는_요청은_확인_불가로_통과한다_한계_명시() throws Exception {
        // getContentLengthLong()은 헤더가 없으면 -1을 반환한다 - 이 필터의 알려진 한계(클래스 주석 참고)
        given(request.getContentLengthLong()).willReturn(-1L);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}
