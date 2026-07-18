package com.tollm.domain.usage;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {
    // TODO [2주차] 사용자별/기간별 집계 JPQL
    //  예: SELECT SUM(l.cost), SUM(l.inputTokens + l.outputTokens) ...
    //  집계는 DTO 프로젝션으로 받기 (엔티티 전체 로딩 금지 - N+1, 메모리 주의)
}
