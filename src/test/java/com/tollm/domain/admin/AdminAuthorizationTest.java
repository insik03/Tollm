package com.tollm.domain.admin;

import com.tollm.domain.apikey.ApiKeyRepository;
import com.tollm.domain.usage.dto.AdminUsageSummaryResponse;
import com.tollm.global.auth.JwtProvider;
import com.tollm.global.config.TollmProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// [SEC-04 보안 수정] AdminControllerTest는 addFilters=false로 JwtAuthFilter를 실행하지 않아,
// "/admin/**은 ADMIN이 아니면 403"이라는 핵심 인가 로직이 실제로는 어떤 자동화 테스트에서도
// 실행되지 않았다(security-engineer 지적). 이 테스트는 @AutoConfigureMockMvc(addFilters=false)를
// 두지 않아(@WebMvcTest 기본값 = addFilters 활성화) 실제 JwtAuthFilter가 요청을 가로채게 하고,
// JwtProvider(서명 검증)만 mock으로 대체해 토큰 파싱 결과(역할)를 직접 제어한다.
// 이렇게 하면 DB/Redis 없이도(슬라이스 테스트) JwtAuthFilter의 실제 분기 로직
// (PROTECTED_PREFIXES 매칭, role != ADMIN 체크)이 그대로 실행된 상태로 검증할 수 있다.
@WebMvcTest(AdminController.class)
class AdminAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminService adminService;

    @MockBean
    private JwtProvider jwtProvider;

    @MockBean
    private ApiKeyRepository apiKeyRepository;

    // RequestSizeLimitFilter(SEC-02)도 이 슬라이스에 함께 올라오므로 의존성을 채워야 한다
    @MockBean
    private TollmProperties tollmProperties;

    @Test
    void 토큰_없이_admin_호출하면_401() throws Exception {
        mockMvc.perform(get("/admin/usage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void MEMBER_토큰으로_admin_호출하면_403() throws Exception {
        stubToken("member-token", 1L, "MEMBER");

        mockMvc.perform(get("/admin/usage").header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ADMIN_토큰으로_admin_호출하면_필터를_통과해_컨트롤러까지_도달한다() throws Exception {
        stubToken("admin-token", 2L, "ADMIN");
        given(adminService.allUsage(any(), any()))
                .willReturn(new AdminUsageSummaryResponse(BigDecimal.ZERO, 0L, 0L, 0L, 0L));

        mockMvc.perform(get("/admin/usage").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    private void stubToken(String token, Long userId, String role) {
        given(jwtProvider.getUserId(token)).willReturn(userId);
        Claims claims = mock(Claims.class);
        given(claims.get("role", String.class)).willReturn(role);
        given(jwtProvider.parse(token)).willReturn(claims);
    }
}
