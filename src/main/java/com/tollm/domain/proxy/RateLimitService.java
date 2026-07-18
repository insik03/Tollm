package com.tollm.domain.proxy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RateLimitService {

    // TODO: StringRedisTemplate 주입

    // TODO [2주차] 토큰 버킷 직접 구현
    //  - Redis 키: "bucket:{userId}" 에 남은 토큰 수 + 마지막 리필 시각 저장
    //  - 요청마다: 경과 시간만큼 리필 -> 토큰 1개 차감 -> 0이면 429
    //  - 주의: GET 후 SET은 동시 요청 시 레이스 컨디션 발생
    //    -> Lua 스크립트로 원자적 실행 (면접 단골 포인트!)
    public boolean tryConsume(Long userId) {
        throw new UnsupportedOperationException("TODO");
    }
}
