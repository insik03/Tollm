package com.tollm.domain.team;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

// domain.usage.UsageQuota(개인 쿼터)와 완전히 같은 패턴을 팀 단위로 병렬로 둔 것.
// 기존 UsageQuota/UsageQuotaRepository는 이미 검증된 코드라 손대지 않고, 같은 설계를
// (원자적 bulk update, 지연 월 리셋) 팀에도 그대로 복제하는 쪽을 택했다 - "재설계"가 아니라 "추가".
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamUsageQuota {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false, unique = true)
    private Team team;

    // RequestLog.cost와 동일한 이유로 scale=8 (기본 DECIMAL(19,2)면 건당 소액 비용이 0으로 잘림)
    @Column(precision = 19, scale = 8)
    private BigDecimal monthlyCostLimit;
    @Column(precision = 19, scale = 8)
    private BigDecimal currentUsage;
    private LocalDate resetAt;

    @Builder
    public TeamUsageQuota(Team team, BigDecimal monthlyCostLimit) {
        this.team = team;
        this.monthlyCostLimit = monthlyCostLimit;
        this.currentUsage = BigDecimal.ZERO;
        this.resetAt = LocalDate.now().plusMonths(1).withDayOfMonth(1);
    }

    public boolean isExceeded() {
        return currentUsage.compareTo(monthlyCostLimit) >= 0;
    }

    public boolean isResetDue() {
        return !LocalDate.now().isBefore(resetAt);
    }

    public void reset() {
        this.currentUsage = BigDecimal.ZERO;
        this.resetAt = LocalDate.now().plusMonths(1).withDayOfMonth(1);
    }

    public void updateLimit(BigDecimal newLimit) {
        this.monthlyCostLimit = newLimit;
    }
}
