package com.tollm.domain.proxy;

import com.tollm.global.config.TollmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

// Redis 없이(mock) 검증: "스크립트 실행 결과(1/0)를 boolean으로 올바르게 옮기는지",
// "capacity/refill/버킷 키 등 파라미터를 올바르게 조립해서 넘기는지" 같은 우리 서비스 코드의
// 책임만 검증한다. Lua 스크립트 자체의 원자성(진짜 동시 요청에서 레이스 컨디션이 없는지)은
// mock으로는 증명할 수 없으므로, 그 부분은 docker-compose(Redis 7) 기동 후 수동 검증 대상으로
// 남긴다 (docs/progress-week2.md 테스트 전략/트러블슈팅 참고).
@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedisScript<Long> tokenBucketScript;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        TollmProperties properties = new TollmProperties();
        properties.getRateLimit().setCapacity(10);
        properties.getRateLimit().setRefillPerSec(1);
        rateLimitService = new RateLimitService(redisTemplate, tokenBucketScript, properties);
    }

    @Test
    void 스크립트가_1을_반환하면_허용() {
        given(redisTemplate.execute(eq(tokenBucketScript), anyList(), any(), any(), any(), any()))
                .willReturn(1L);

        assertThat(rateLimitService.tryConsume(42L)).isTrue();
    }

    @Test
    void 스크립트가_0을_반환하면_거부() {
        given(redisTemplate.execute(eq(tokenBucketScript), anyList(), any(), any(), any(), any()))
                .willReturn(0L);

        assertThat(rateLimitService.tryConsume(42L)).isFalse();
    }

    @Test
    void 스크립트_결과가_null이어도_안전하게_거부로_처리() {
        given(redisTemplate.execute(eq(tokenBucketScript), anyList(), any(), any(), any(), any()))
                .willReturn(null);

        assertThat(rateLimitService.tryConsume(42L)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 버킷_키에_userId가_포함되고_설정값이_그대로_전달된다() {
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        given(redisTemplate.execute(eq(tokenBucketScript), keysCaptor.capture(),
                eq("10"), eq("1"), any(), eq("1")))
                .willReturn(1L);

        rateLimitService.tryConsume(42L);

        assertThat(keysCaptor.getValue()).containsExactly("bucket:42");
    }

    // ---- 팀 키(add-on) ----

    @Test
    void 팀_버킷도_스크립트_결과를_그대로_boolean으로_옮긴다() {
        given(redisTemplate.execute(eq(tokenBucketScript), anyList(), any(), any(), any(), any()))
                .willReturn(1L);

        assertThat(rateLimitService.tryConsumeForTeam(7L)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 팀_버킷_키는_개인_버킷과_다른_네임스페이스를_쓴다() {
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        given(redisTemplate.execute(eq(tokenBucketScript), keysCaptor.capture(), any(), any(), any(), any()))
                .willReturn(1L);

        rateLimitService.tryConsumeForTeam(42L);

        // 개인 버킷("bucket:42")과 팀 버킷("bucket:team:42")이 같은 숫자 42여도 절대 충돌하지 않는다
        assertThat(keysCaptor.getValue()).containsExactly("bucket:team:42");
    }
}
