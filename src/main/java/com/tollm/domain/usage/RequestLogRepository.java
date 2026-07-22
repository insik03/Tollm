package com.tollm.domain.usage;

import com.tollm.domain.usage.dto.AdminUsageSummaryResponse;
import com.tollm.domain.usage.dto.TeamUsageSummaryResponse;
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
    //
    // [버그 수정] l.team IS NULL 조건이 원래 빠져 있었다 - RequestLog.user는 팀 키 요청에서도
    // "키를 발급한 사람"으로 계속 채워지므로(RequestLog 주석 참고), 이 조건 없이는 팀원이 쓴
    // 팀 키 사용량까지 그 사람의 개인 /usage/me에 합산돼 보였다(팀/개인 구분이 안 됨 - 실사용
    // 중 발견). team이 채워진 행(팀 키 사용)은 여기서 제외하고 /teams/{id}/usage 쪽에서만 집계한다.
    @Query("""
            SELECT new com.tollm.domain.usage.dto.UsageSummaryResponse(
                COALESCE(SUM(l.cost), 0),
                COALESCE(SUM(l.inputTokens + l.outputTokens), 0),
                COUNT(l),
                COALESCE(SUM(CASE WHEN l.cacheHit = true THEN 1L ELSE 0L END), 0)
            )
            FROM RequestLog l
            WHERE l.user.id = :userId AND l.team IS NULL AND l.createdAt BETWEEN :from AND :to
            """)
    UsageSummaryResponse aggregateByUser(@Param("userId") Long userId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    // API 키 1개 단위 집계 (개인 키든 팀 키든 동일 쿼리) - 사용자가 키를 여러 개 발급받아도
    // 서로 섞이지 않고 각자 얼마나 썼는지 구분할 수 있다. 소유권/멤버십 검증은 서비스 계층 책임.
    @Query("""
            SELECT new com.tollm.domain.usage.dto.UsageSummaryResponse(
                COALESCE(SUM(l.cost), 0),
                COALESCE(SUM(l.inputTokens + l.outputTokens), 0),
                COUNT(l),
                COALESCE(SUM(CASE WHEN l.cacheHit = true THEN 1L ELSE 0L END), 0)
            )
            FROM RequestLog l
            WHERE l.apiKey.id = :apiKeyId AND l.createdAt BETWEEN :from AND :to
            """)
    UsageSummaryResponse aggregateByApiKey(@Param("apiKeyId") Long apiKeyId,
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

    // /teams/{id}/usage - aggregateByUser와 완전히 같은 구조, l.team.id로만 필터한다.
    // 팀 키가 아닌 개인 키 요청(team이 null인 행)은 여기 절대 안 잡힌다 - 팀/개인 집계가 서로 섞이지 않는다.
    @Query("""
            SELECT new com.tollm.domain.usage.dto.TeamUsageSummaryResponse(
                COALESCE(SUM(l.cost), 0),
                COALESCE(SUM(l.inputTokens + l.outputTokens), 0),
                COUNT(l),
                COALESCE(SUM(CASE WHEN l.cacheHit = true THEN 1L ELSE 0L END), 0)
            )
            FROM RequestLog l
            WHERE l.team.id = :teamId AND l.createdAt BETWEEN :from AND :to
            """)
    TeamUsageSummaryResponse aggregateByTeam(@Param("teamId") Long teamId,
                                              @Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to);
}
