package com.tollm.domain.proxy;

import com.tollm.global.config.TollmProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> tokenBucketScript;
    private final TollmProperties properties;

    // Redis 키: "bucket:{userId}"에 남은 토큰 수 + 마지막 리필 시각을 저장.
    // 리필 계산 + 차감은 token_bucket.lua 스크립트가 Redis 안에서 원자적으로 처리한다
    // (GET 후 SET을 애플리케이션에서 하면 동시 요청 시 레이스 컨디션 발생 - RedisConfig 주석 참고).
    //
    // 이 메서드는 boolean만 반환하고 429로의 변환은 호출부(ProxyService)의 책임으로 남긴다:
    // "토큰이 있는지 없는지"와 "그래서 어떤 HTTP 상태코드/메시지를 줄지"는 서로 다른 관심사다.
    public boolean tryConsume(Long userId) {
        TollmProperties.RateLimit rateLimit = properties.getRateLimit();
        String key = "bucket:" + userId;

        Long allowed = redisTemplate.execute(
                tokenBucketScript,
                List.of(key),
                String.valueOf(rateLimit.getCapacity()),
                String.valueOf(rateLimit.getRefillPerSec()),
                String.valueOf(System.currentTimeMillis()),
                "1"
        );
        return allowed != null && allowed == 1L;
    }
}
