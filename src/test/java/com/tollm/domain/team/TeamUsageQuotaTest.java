package com.tollm.domain.team;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

// domain.usage.UsageQuotaTest와 완전히 같은 케이스 - TeamUsageQuota가 그 패턴을 그대로 복제했음을 검증
class TeamUsageQuotaTest {

    private Team team() {
        return Team.builder().name("백엔드 스터디").build();
    }

    @Test
    void 사용량이_한도_미만이면_초과가_아니다() {
        TeamUsageQuota quota = TeamUsageQuota.builder().team(team()).monthlyCostLimit(BigDecimal.TEN).build();

        assertThat(quota.isExceeded()).isFalse();
    }

    @Test
    void 한도가_0이면_사용량0이어도_바로_초과다() {
        TeamUsageQuota quota = TeamUsageQuota.builder().team(team()).monthlyCostLimit(BigDecimal.ZERO).build();

        assertThat(quota.isExceeded()).isTrue();
    }

    @Test
    void 생성_직후에는_resetAt이_다음달_1일이라_리셋대상이_아니다() {
        TeamUsageQuota quota = TeamUsageQuota.builder().team(team()).monthlyCostLimit(BigDecimal.TEN).build();

        assertThat(quota.isResetDue()).isFalse();
    }

    @Test
    void reset하면_사용량이_0으로_초기화되고_다음_리셋일이_다음달_1일로_갱신된다() {
        TeamUsageQuota quota = TeamUsageQuota.builder().team(team()).monthlyCostLimit(BigDecimal.TEN).build();

        quota.reset();

        assertThat(quota.getCurrentUsage()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(quota.getResetAt()).isEqualTo(LocalDate.now().plusMonths(1).withDayOfMonth(1));
    }

    @Test
    void updateLimit으로_한도를_변경할_수_있다() {
        TeamUsageQuota quota = TeamUsageQuota.builder().team(team()).monthlyCostLimit(BigDecimal.TEN).build();

        quota.updateLimit(BigDecimal.valueOf(50));

        assertThat(quota.getMonthlyCostLimit()).isEqualByComparingTo(BigDecimal.valueOf(50));
    }
}
