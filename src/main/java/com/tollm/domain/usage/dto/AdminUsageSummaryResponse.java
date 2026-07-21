package com.tollm.domain.usage.dto;

import java.math.BigDecimal;

// /admin/usage 전용 - 사용자 구분 없이 합산 + 활동한 사용자 수(activeUserCount)를 함께 내려준다.
// usage 패키지에 둔 이유: 집계 대상(RequestLog)이 usage 도메인 소속이라 그 리포지토리(RequestLogRepository)와
// 같은 패키지에 있어야 JPQL의 "new 패키지경로.클래스명(...)" 생성자 표현식이 자연스럽고,
// admin 도메인이 usage 도메인 데이터를 조회해 보여주는 방향(admin -> usage 의존)이 맞다.
public record AdminUsageSummaryResponse(
        BigDecimal totalCost,
        Long totalTokens,
        Long requestCount,
        Long cacheHitCount,
        Long activeUserCount
) {
    public double cacheHitRate() {
        return requestCount == 0 ? 0.0 : (double) cacheHitCount / requestCount;
    }
}
