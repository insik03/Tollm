package com.tollm.domain.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollm.domain.provider.LlmModel;
import com.tollm.domain.provider.LlmModelRepository;
import com.tollm.domain.provider.Provider;
import com.tollm.domain.proxy.client.LlmClient;
import com.tollm.domain.usage.RequestLogRepository;
import com.tollm.domain.usage.UsageQuota;
import com.tollm.domain.usage.UsageQuotaRepository;
import com.tollm.domain.user.Role;
import com.tollm.domain.user.User;
import com.tollm.domain.user.UserRepository;
import com.tollm.global.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

// mock 기반 단위 테스트를 선택한 이유: relay()는 Redis(레이트리밋)/DB(쿼터,로그)/외부 LLM API
// 세 가지 인프라에 동시에 의존한다. 이 테스트의 목적은 "인프라가 실제로 동작하는지"가 아니라
// "레이트리밋->쿼터->캐시->라우팅->비용계산->로깅의 순서와 분기 로직이 올바른지"이므로,
// 각 협력 객체를 Mockito로 대체해 이 오케스트레이션 로직만 빠르고 안정적으로 검증한다.
@ExtendWith(MockitoExtension.class)
class ProxyServiceTest {

    @Mock private ProviderRouter providerRouter;
    @Mock private RateLimitService rateLimitService;
    @Mock private ResponseCacheService responseCacheService;
    @Mock private UsageQuotaRepository usageQuotaRepository;
    @Mock private RequestLogRepository requestLogRepository;
    @Mock private LlmModelRepository llmModelRepository;
    @Mock private UserRepository userRepository;
    @Mock private LlmClient llmClient;

    private ProxyService proxyService;

    private static final Long USER_ID = 1L;
    private static final String BODY =
            "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    @BeforeEach
    void setUp() {
        proxyService = new ProxyService(providerRouter, new ObjectMapper(), rateLimitService,
                responseCacheService, usageQuotaRepository, requestLogRepository,
                llmModelRepository, userRepository);
    }

    private UsageQuota quotaWithLimit(BigDecimal limit) {
        User user = User.builder().email("u@test.com").password("h").role(Role.MEMBER).build();
        return UsageQuota.builder().user(user).monthlyCostLimit(limit).build();
    }

    @Test
    void 레이트리밋_초과시_429_예외이고_이후_단계는_호출되지_않는다() {
        given(rateLimitService.tryConsume(USER_ID)).willReturn(false);

        assertThatThrownBy(() -> proxyService.relay(USER_ID, BODY))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(429));

        verifyNoInteractions(providerRouter, responseCacheService, requestLogRepository, usageQuotaRepository);
    }

    @Test
    void 쿼터_초과시_429_예외이고_라우팅은_호출되지_않는다() {
        given(rateLimitService.tryConsume(USER_ID)).willReturn(true);
        given(usageQuotaRepository.findByUserId(USER_ID))
                .willReturn(Optional.of(quotaWithLimit(BigDecimal.ZERO))); // 한도 0 -> 즉시 초과 상태

        assertThatThrownBy(() -> proxyService.relay(USER_ID, BODY))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(429));

        verifyNoInteractions(providerRouter, responseCacheService);
    }

    @Test
    void 쿼터_정보가_없으면_404() {
        given(rateLimitService.tryConsume(USER_ID)).willReturn(true);
        given(usageQuotaRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> proxyService.relay(USER_ID, BODY))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(404));
    }

    // 등록된 모델(gpt-4o-mini)의 LlmModel을 반환하도록 stub - SEC-03 수정 이후
    // relay()는 라우팅/캐시 조회 전에 단가표 등록 여부부터 확인하므로, 캐시 히트 케이스를 포함해
    // model 검증을 통과해야 하는 모든 테스트에서 이 stub이 필요하다.
    private LlmModel registeredModel() {
        Provider provider = Provider.builder().name("openai").baseUrl("https://api.openai.com").build();
        return LlmModel.builder()
                .provider(provider).name("gpt-4o-mini")
                .inputPricePer1m(BigDecimal.valueOf(150))
                .outputPricePer1m(BigDecimal.valueOf(600))
                .build();
    }

    @Test
    void 캐시_히트시_외부_호출없이_즉시_반환하고_비용0으로_로깅한다() {
        given(rateLimitService.tryConsume(USER_ID)).willReturn(true);
        given(usageQuotaRepository.findByUserId(USER_ID)).willReturn(Optional.of(quotaWithLimit(BigDecimal.TEN)));
        given(llmModelRepository.findByName("gpt-4o-mini")).willReturn(Optional.of(registeredModel()));
        given(providerRouter.route("gpt-4o-mini")).willReturn(llmClient);
        given(llmClient.providerName()).willReturn("openai");
        given(responseCacheService.buildKey(eq(USER_ID), any())).willReturn("cache:key");
        given(responseCacheService.get("cache:key")).willReturn("{\"cached\":true}");
        given(userRepository.getReferenceById(USER_ID)).willReturn(mock(User.class));

        String result = proxyService.relay(USER_ID, BODY);

        assertThat(result).isEqualTo("{\"cached\":true}");
        verify(llmClient, never()).chat(anyString());
        verify(requestLogRepository).save(argThat(log -> log.isCacheHit() && log.getCost().signum() == 0));
        verify(usageQuotaRepository, never()).addUsage(any(), any());
        verify(responseCacheService, never()).put(anyString(), anyString());
    }

    @Test
    void 캐시_미스시_라우팅후_토큰기반_비용을_계산해_쿼터에_누적하고_캐시에_저장한다() {
        given(rateLimitService.tryConsume(USER_ID)).willReturn(true);
        given(usageQuotaRepository.findByUserId(USER_ID)).willReturn(Optional.of(quotaWithLimit(BigDecimal.TEN)));
        given(providerRouter.route("gpt-4o-mini")).willReturn(llmClient);
        given(llmClient.providerName()).willReturn("openai");
        given(responseCacheService.buildKey(eq(USER_ID), any())).willReturn("cache:key");
        given(responseCacheService.get("cache:key")).willReturn(null);
        given(llmClient.chat(BODY)).willReturn("{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50}}");
        given(llmModelRepository.findByName("gpt-4o-mini")).willReturn(Optional.of(registeredModel()));
        given(userRepository.getReferenceById(USER_ID)).willReturn(mock(User.class));

        proxyService.relay(USER_ID, BODY);

        // 100 * 150 / 1_000_000 + 50 * 600 / 1_000_000 = 0.015 + 0.03 = 0.045
        verify(usageQuotaRepository).addUsage(eq(USER_ID), eq(BigDecimal.valueOf(0.045).setScale(8)));
        verify(responseCacheService).put(eq("cache:key"), anyString());
        verify(requestLogRepository).save(argThat(log ->
                !log.isCacheHit() && log.getInputTokens() == 100 && log.getOutputTokens() == 50));
    }

    // [SEC-03 보안 수정] 단가표에 없는 모델은 외부 호출/캐시 조회 전에 400으로 거부되어야 한다.
    // 이전 동작(비용 0으로 기록하고 응답은 정상 반환)은 실제 프로바이더 비용은 발생했는데
    // 쿼터에는 반영되지 않아 월 한도를 우회할 수 있었다(security-engineer 지적) - 게이트웨이가
    // 과금을 보장 못 하는 요청은 아예 통과시키지 않는 원칙으로 변경했다.
    @Test
    void 단가정보가_없는_모델은_외부_호출_전에_400으로_거부된다() {
        given(rateLimitService.tryConsume(USER_ID)).willReturn(true);
        given(usageQuotaRepository.findByUserId(USER_ID)).willReturn(Optional.of(quotaWithLimit(BigDecimal.TEN)));
        given(llmModelRepository.findByName("gpt-4o-mini")).willReturn(Optional.empty());

        assertThatThrownBy(() -> proxyService.relay(USER_ID, BODY))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(400));

        verifyNoInteractions(providerRouter, responseCacheService, llmClient, requestLogRepository);
        verify(usageQuotaRepository, never()).addUsage(any(), any());
    }

    @Test
    void model_필드가_없으면_400() {
        given(rateLimitService.tryConsume(USER_ID)).willReturn(true);
        given(usageQuotaRepository.findByUserId(USER_ID)).willReturn(Optional.of(quotaWithLimit(BigDecimal.TEN)));

        assertThatThrownBy(() -> proxyService.relay(USER_ID, "{}"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(400));
    }
}
