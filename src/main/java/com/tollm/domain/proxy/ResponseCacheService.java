package com.tollm.domain.proxy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ResponseCacheService {

    // TODO: StringRedisTemplate 주입

    // TODO [2주차] 요청 정규화 기반 exact 캐시
    //  - 캐시 키: model + messages를 정규화(공백 정리 등)해 SHA-256 해시
    //  - TTL 설정 (예: 1시간). 캐시 히트/미스 카운트도 기록해 히트율 측정
    //  - [도전] 임베딩 유사도 기반 시맨틱 캐시로 확장
    public String get(String cacheKey) { throw new UnsupportedOperationException("TODO"); }

    public void put(String cacheKey, String response) { throw new UnsupportedOperationException("TODO"); }
}
