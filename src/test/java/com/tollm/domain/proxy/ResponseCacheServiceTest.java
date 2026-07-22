package com.tollm.domain.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollm.global.config.TollmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ResponseCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ResponseCacheService cacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TollmProperties properties = new TollmProperties();
        properties.getCache().setTtlSeconds(3600);
        cacheService = new ResponseCacheService(redisTemplate, properties);
    }

    private JsonNode parse(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    @Test
    void 같은_사용자_같은_요청은_같은_키를_만든다() throws Exception {
        JsonNode root = parse("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");

        assertThat(cacheService.buildKey(1L, root)).isEqualTo(cacheService.buildKey(1L, root));
    }

    // [보안] 사용자 간 캐시 크로스오버 방지 검증 - security-engineer 검수 포인트
    @Test
    void 사용자가_다르면_같은_요청이어도_다른_키가_나온다() throws Exception {
        JsonNode root = parse("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");

        assertThat(cacheService.buildKey(1L, root)).isNotEqualTo(cacheService.buildKey(2L, root));
    }

    @Test
    void 공백_차이만_있으면_같은_키로_정규화된다() throws Exception {
        JsonNode a = parse("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hello   world\"}]}");
        JsonNode b = parse("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"  hello world  \"}]}");

        assertThat(cacheService.buildKey(1L, a)).isEqualTo(cacheService.buildKey(1L, b));
    }

    @Test
    void 내용이_다르면_다른_키가_나온다() throws Exception {
        JsonNode a = parse("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
        JsonNode b = parse("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"world\"}]}");

        assertThat(cacheService.buildKey(1L, a)).isNotEqualTo(cacheService.buildKey(1L, b));
    }

    @Test
    void get은_redis_값을_그대로_반환한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("cache:key")).willReturn("cached-response");

        assertThat(cacheService.get("cache:key")).isEqualTo("cached-response");
    }

    @Test
    void get은_miss시_null을_반환한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("cache:key")).willReturn(null);

        assertThat(cacheService.get("cache:key")).isNull();
    }

    @Test
    void put은_설정된_TTL을_적용해서_저장한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        cacheService.put("cache:key", "response-body");

        verify(valueOperations).set("cache:key", "response-body", Duration.ofSeconds(3600));
    }

    // ---- 팀 키(add-on) ----

    @Test
    void 같은_팀_같은_요청은_같은_키를_만든다() throws Exception {
        JsonNode root = parse("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");

        assertThat(cacheService.buildKeyForTeam(1L, root)).isEqualTo(cacheService.buildKeyForTeam(1L, root));
    }

    @Test
    void 팀이_다르면_다른_키가_나온다() throws Exception {
        JsonNode root = parse("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");

        assertThat(cacheService.buildKeyForTeam(1L, root)).isNotEqualTo(cacheService.buildKeyForTeam(2L, root));
    }

    // [설계 의도] 개인 캐시(buildKey)와 팀 캐시(buildKeyForTeam)는 id 값이 같아도(둘 다 1L)
    // "team:" 접두어로 네임스페이스가 분리되어 있어 절대 같은 키가 나오지 않는다 -
    // 팀 캐시 도입으로 기존 개인 캐시 항목과 크로스오버가 생기지 않음을 보장하는 핵심 검증
    @Test
    void 개인_캐시와_팀_캐시는_같은_id여도_절대_같은_키가_아니다() throws Exception {
        JsonNode root = parse("{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");

        assertThat(cacheService.buildKey(1L, root)).isNotEqualTo(cacheService.buildKeyForTeam(1L, root));
    }
}
