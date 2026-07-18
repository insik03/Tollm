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

    private BigDecimal monthlyCostLimit;
    private BigDecimal currentUsage;
    private LocalDate resetAt;

    @Builder
    public UsageQuota(User user, BigDecimal monthlyCostLimit) {
        this.user = user;
        this.monthlyCostLimit = monthlyCostLimit;
        this.currentUsage = BigDecimal.ZERO;
        this.resetAt = LocalDate.now().plusMonths(1).withDayOfMonth(1);
    }

    // TODO [2주차] addUsage(cost): 사용량 누적. 동시 요청 시 갱신 유실 문제 고민해볼 것
    // TODO [2주차] isExceeded(): 한도 초과 여부
}
