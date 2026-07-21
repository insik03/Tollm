package com.tollm.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

    // StringRedisTemplate 빈을 직접 등록하지 않는 이유:
    // spring-boot-starter-data-redis가 클래스패스에 있으면 Boot의 RedisAutoConfiguration이
    // RedisConnectionFactory를 보고 StringRedisTemplate 빈을 이미 자동 등록해 준다.
    // 직렬화 방식(문자열)을 바꿀 필요가 없는 한 자동설정과 중복 정의할 이유가 없다.

    // 토큰 버킷 리필+차감을 원자적으로 실행하는 Lua 스크립트 빈.
    // DefaultRedisScript로 감싸서 스크립트를 SHA로 캐싱(EVALSHA)하게 하면 매 요청마다
    // 스크립트 본문 전체를 Redis로 보내지 않아도 된다 (RedisScript의 기본 동작).
    @Bean
    public RedisScript<Long> tokenBucketScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
