package com.tollm.domain.usage;

import com.tollm.domain.apikey.ApiKeyRepository;
import com.tollm.domain.usage.dto.RequestLogResponse;
import com.tollm.domain.usage.dto.UsageSummaryResponse;
import com.tollm.global.auth.JwtProvider;
import com.tollm.global.config.TollmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// @WebMvcTest는 컨트롤러 계층만 슬라이스로 띄운다 - JwtAuthFilter 등 커스텀 서블릿 필터는
// 이 슬라이스에 자동 등록되지 않으므로, 필터가 정상 인증 후 넣어주는 request attribute(userId)를
// requestAttr()로 직접 시뮬레이션한다 (필터 자체의 동작은 JwtAuthFilter 담당이라 여기서 재검증하지 않음).
// addFilters = false: JwtAuthFilter/ApiKeyAuthFilter는 이 슬라이스 컨텍스트에도 빈으로 올라오지만
// (@Component + OncePerRequestFilter라 자동 포함됨), 필터 자체의 인증 로직은 이 테스트의 관심사가
// 아니므로(그건 JwtAuthFilter 담당) 실행되지 않게 끄고, 필터가 정상 인증했다고 가정한 상태를
// requestAttr()로 직접 시뮬레이션한다.
@WebMvcTest(UsageController.class)
@AutoConfigureMockMvc(addFilters = false)
class UsageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UsageService usageService;

    // @WebMvcTest는 커스텀 Filter(@Component + OncePerRequestFilter)도 컨텍스트에 함께 올리므로
    // JwtAuthFilter/ApiKeyAuthFilter/RequestSizeLimitFilter가 요구하는 의존성도 mock으로 채워줘야
    // 컨텍스트가 뜬다 (필터 로직 자체는 여기서 검증하지 않는다 - requestAttr()로 필터 통과 후
    // 상태를 직접 시뮬레이션)
    @MockBean
    private JwtProvider jwtProvider;

    @MockBean
    private ApiKeyRepository apiKeyRepository;

    @MockBean
    private TollmProperties tollmProperties;

    @Test
    void 내_사용량_조회는_집계_DTO를_JSON으로_반환한다() throws Exception {
        given(usageService.myUsage(eq(1L), any(), any()))
                .willReturn(new UsageSummaryResponse(BigDecimal.valueOf(1.23), 300L, 3L, 1L));

        mockMvc.perform(get("/usage/me").requestAttr("userId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCost").value(1.23))
                .andExpect(jsonPath("$.requestCount").value(3))
                .andExpect(jsonPath("$.cacheHitCount").value(1))
                // User 등 엔티티 필드(email, password 등)가 응답에 섞여 나오지 않는지 확인
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void 내_로그_조회는_페이징된_DTO_목록을_반환한다() throws Exception {
        RequestLogResponse log = new RequestLogResponse(
                1L, "gpt-4o-mini", "openai", 10, 5, BigDecimal.ONE, 120L, 200, false, LocalDateTime.now());
        Page<RequestLogResponse> page = new PageImpl<>(List.of(log), PageRequest.of(0, 20), 1);
        given(usageService.myLogs(eq(1L), any())).willReturn(page);

        mockMvc.perform(get("/usage/me/logs").requestAttr("userId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].model").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.content[0].providerName").value("openai"))
                .andExpect(jsonPath("$.content[0].user").doesNotExist());
    }
}
