package com.tollm.domain.admin;

import com.tollm.domain.apikey.ApiKeyRepository;
import com.tollm.domain.usage.dto.AdminUsageSummaryResponse;
import com.tollm.global.auth.JwtProvider;
import com.tollm.global.config.TollmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 이 테스트는 addFilters=false로 JwtAuthFilter 실행을 끄고 "필터를 통과했다고 가정했을 때
// 컨트롤러가 올바른 DTO/상태코드를 돌려주는지"만 검증한다 (요청 바인딩/응답 직렬화 관심사).
// 인가(ADMIN 전용) 필터 로직 자체가 실제로 403/401을 반환하는지는 AdminAuthorizationTest에서
// addFilters를 켠 상태로 별도 검증한다 (SEC-04 보안 수정 - 이전에는 이 검증이 어디에도 없었다).
@WebMvcTest(AdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminService adminService;

    // @WebMvcTest는 커스텀 Filter(@Component + OncePerRequestFilter)도 컨텍스트에 함께 올리므로
    // JwtAuthFilter/ApiKeyAuthFilter/RequestSizeLimitFilter가 요구하는 의존성도 mock으로 채워줘야
    // 컨텍스트가 뜬다
    @MockBean
    private JwtProvider jwtProvider;

    @MockBean
    private ApiKeyRepository apiKeyRepository;

    @MockBean
    private TollmProperties tollmProperties;

    @Test
    void 전체_사용량_통계는_집계_DTO를_반환한다() throws Exception {
        given(adminService.allUsage(any(), any()))
                .willReturn(new AdminUsageSummaryResponse(BigDecimal.TEN, 1000L, 10L, 3L, 2L));

        mockMvc.perform(get("/admin/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCost").value(10))
                .andExpect(jsonPath("$.activeUserCount").value(2));
    }

    @Test
    void 쿼터_설정은_204를_반환하고_서비스에_위임한다() throws Exception {
        mockMvc.perform(patch("/admin/quota/{userId}", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyCostLimit\": 20.0}"))
                .andExpect(status().isNoContent());

        verify(adminService).setQuota(7L, BigDecimal.valueOf(20.0));
    }

    @Test
    void 쿼터_설정시_한도가_0이하이면_400() throws Exception {
        mockMvc.perform(patch("/admin/quota/{userId}", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyCostLimit\": 0}"))
                .andExpect(status().isBadRequest());
    }
}
