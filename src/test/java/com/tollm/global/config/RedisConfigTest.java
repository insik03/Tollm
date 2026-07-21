package com.tollm.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;

class RedisConfigTest {

    // Redis 연결 없이도(스크립트 리소스 로드는 순수 파일 읽기라) 검증 가능한 부분만 확인.
    // 실제 Redis에 대한 EVALSHA 동작 검증은 RateLimitService의 목적행위(Mockito) 테스트와
    // docker-compose 기반 수동 검증으로 커버한다 (docs/progress-week2.md 참고).
    @Test
    void 토큰버킷_스크립트가_클래스패스에서_정상적으로_로드된다() {
        RedisConfig config = new RedisConfig();
        RedisScript<Long> script = config.tokenBucketScript();

        assertThat(script.getResultType()).isEqualTo(Long.class);
        assertThat(script.getScriptAsString()).contains("HMGET").contains("HMSET").contains("EXPIRE");
    }
}
