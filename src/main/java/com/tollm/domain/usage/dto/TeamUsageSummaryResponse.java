package com.tollm.domain.usage.dto;

import java.math.BigDecimal;

// /teams/{id}/usage 전용. AdminUsageSummaryResponse와 같은 이유로 usage.dto에 둔다
// (집계 대상 RequestLog가 usage 도메인 소속 - JPQL "new 패키지경로.클래스명(...)" 생성자 표현식이 자연스러움).
public record TeamUsageSummaryResponse(
        BigDecimal totalCost,
        Long totalTokens,
        Long requestCount,
        Long cacheHitCount
) {
    public double cacheHitRate() {
        return requestCount == 0 ? 0.0 : (double) cacheHitCount / requestCount;
    }
}
