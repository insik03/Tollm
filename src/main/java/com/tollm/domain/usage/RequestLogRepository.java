package com.tollm.domain.usage;

import com.tollm.domain.usage.dto.AdminUsageSummaryResponse;
import com.tollm.domain.usage.dto.UsageSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {

    // /usage/me/logs - 본인 로그 페이징. 엔티티를 그대로 반환하되 서비스 계층에서 즉시 DTO로
    // 변환하므로(UsageService) 컨트롤러 밖으로 엔티티가 나가지 않는다.
    Page<RequestLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // /usage/me - 사용자별 기간 집계. 엔티티 전체 로딩(findByUserId + 스트림 합산) 대신
    // DB에서 SUM/COUNT로 집계한 뒤 record 생성자 프로젝션으로 바로 UsageSummaryResponse를 채운다
    // (대용량 로그 테이블 - N+1/메모리 낭비 방지). 로그가 0건이어도 COUNT는 0을, SUM은 NULL을
    // 반환하므로 COALESCE로 0을 기본값으로 맞춰 컨트롤러 쪽에서 null 처리를 하지 않게 한다.
    @Query("""
            SELECT new com.tollm.domain.usage.dto.UsageSummaryResponse(
                COALESCE(SUM(l.cost), 0),
                COALESCE(SUM(l.inputTokens + l.outputTokens), 0),
                COUNT(l),
                COALESCE(SUM(CASE WHEN l.cacheHit = true THEN 1L ELSE 0L END), 0)
            )
            FROM RequestLog l
            WHERE l.user.id = :userId AND l.createdAt BETWEEN :from AND :to
            """)
    UsageSummaryResponse aggregateByUser(@Param("userId") Long userId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    // /admin/usage - 사용자 구분 없이 전체 합산 + 활동한 사용자 수(COUNT DISTINCT)
    @Query("""
            SELECT new com.tollm.domain.usage.dto.AdminUsageSummaryResponse(
                COALESCE(SUM(l.cost), 0),
                COALESCE(SUM(l.inputTokens + l.outputTokens), 0),
                COUNT(l),
                COALESCE(SUM(CASE WHEN l.cacheHit = true THEN 1L ELSE 0L END), 0),
                COUNT(DISTINCT l.user.id)
            )
            FROM RequestLog l
            WHERE l.createdAt BETWEEN :from AND :to
            """)
    AdminUsageSummaryResponse aggregateAll(@Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);
}
