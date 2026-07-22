package com.tollm.domain.usage;

import com.tollm.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsageQuota {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // RequestLog.cost와 같은 이유로 scale=8 지정 - 기본 DECIMAL(19,2)이면 addUsage로 누적되는
    // 소액(건당 $0.01 미만)이 매번 0으로 잘려 쿼터가 사실상 영원히 차지 않는다.
    @Column(precision = 19, scale = 8)
    private BigDecimal monthlyCostLimit;
    @Column(precision = 19, scale = 8)
    private BigDecimal currentUsage;
    private LocalDate resetAt;

    @Builder
    public UsageQuota(User user, BigDecimal monthlyCostLimit) {
        this.user = user;
        this.monthlyCostLimit = monthlyCostLimit;
        this.currentUsage = BigDecimal.ZERO;
        this.resetAt = LocalDate.now().plusMonths(1).withDayOfMonth(1);
    }

    // 사용량 누적(addUsage)은 이 엔티티에 두지 않는다: dirty checking(이 값을 읽어서 더한 뒤 커밋 시
    // UPDATE)은 결국 "읽고-더하고-쓰기"라서 동시 요청 두 개가 같은 스냅샷을 읽으면 하나의 갱신이
    // 사라지는 lost update가 발생한다. 그래서 원자적 누적은 UsageQuotaRepository.addUsage()의
    // JPQL bulk UPDATE(DB가 "현재 값 + cost"를 한 번의 SQL로 처리)에 위임한다 - Redis Lua 스크립트로
    // 레이트리밋의 원자성을 확보한 것과 같은 철학이다 (README 기술 선정 근거 참고)

    // 조회 시점 스냅샷 기준 판단. bulk UPDATE 직후의 최신값을 완벽히 반영하지 않을 수 있지만
    // (예: 이 판단 이후 다른 요청이 addUsage로 한도를 넘겨도 이번 요청은 통과) 이는 쿼터처럼
    // "정확히 한 번만 차단"이 아니라 "월 한도를 크게 넘기지 않게 통제"가 목적인 기능에서 허용 가능한 오차다
    public boolean isExceeded() {
        return currentUsage.compareTo(monthlyCostLimit) >= 0;
    }

    // 월 리셋: 별도 배치/스케줄러 없이 요청 시점에 지연 평가(lazy reset)한다.
    // resetAt는 매달 1일 경계에서만 참이 되는 드문 이벤트라, 이 시점 판단에 별도 동시성 보호를
    // 추가하지 않아도 실사용에서 문제가 되지 않는다고 판단했다 (PRD "확인 필요" 항목 중 최소 구현).
    public boolean isResetDue() {
        return !LocalDate.now().isBefore(resetAt);
    }

    public void reset() {
        this.currentUsage = BigDecimal.ZERO;
        this.resetAt = LocalDate.now().plusMonths(1).withDayOfMonth(1);
    }

    // 관리자가 사용자 쿼터 한도를 변경할 때 사용 (AdminService)
    public void updateLimit(BigDecimal newLimit) {
        this.monthlyCostLimit = newLimit;
    }
}
