package com.tollm.domain.team;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

// domain.usage.UsageQuotaRepository와 완전히 같은 원자적 bulk UPDATE 패턴 (lost update 방지 - 근거는 그쪽 주석 참고)
public interface TeamUsageQuotaRepository extends JpaRepository<TeamUsageQuota, Long> {

    Optional<TeamUsageQuota> findByTeamId(Long teamId);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE TeamUsageQuota q SET q.currentUsage = q.currentUsage + :cost WHERE q.team.id = :teamId")
    int addUsage(@Param("teamId") Long teamId, @Param("cost") BigDecimal cost);
}
