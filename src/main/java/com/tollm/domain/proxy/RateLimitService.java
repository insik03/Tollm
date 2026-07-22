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
        return consume("bucket:" + userId);
    }

    // 팀 키 요청 전용 (add-on) - 버킷 키를 "bucket:team:{id}"로 분리해서 개인 버킷("bucket:{userId}")과
    // 절대 충돌하지 않는다. 이 메서드가 추가되기 전 tryConsume(Long)의 키 형식/동작은 한 글자도 바뀌지 않았다.
    public boolean tryConsumeForTeam(Long teamId) {
        return consume("bucket:team:" + teamId);
    }

    private boolean consume(String key) {
        TollmProperties.RateLimit rateLimit = properties.getRateLimit();

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
