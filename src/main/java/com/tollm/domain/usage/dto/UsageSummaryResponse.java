package com.tollm.domain.usage.dto;

import java.math.BigDecimal;

// 사용자별 기간 집계 결과 - RequestLogRepository의 JPQL 생성자(record) 프로젝션이 바로 이 타입으로
// 채워준다. 엔티티(RequestLog)를 통째로 불러와 애플리케이션에서 합산하지 않는다 (대용량 로그 테이블
// 전체 로딩 방지 - N+1, 메모리 낭비 방지가 목적).
public record UsageSummaryResponse(
        BigDecimal totalCost,
        Long totalTokens,
        Long requestCount,
        Long cacheHitCount
) {
    public double cacheHitRate() {
        return requestCount == 0 ? 0.0 : (double) cacheHitCount / requestCount;
    }
}
