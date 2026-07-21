package com.tollm.domain.usage;

import com.tollm.domain.user.Role;
import com.tollm.domain.user.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class UsageQuotaTest {

    private User user() {
        return User.builder().email("u@test.com").password("hash").role(Role.MEMBER).build();
    }

    @Test
    void 사용량이_한도_미만이면_초과가_아니다() {
        UsageQuota quota = UsageQuota.builder().user(user()).monthlyCostLimit(BigDecimal.TEN).build();

        assertThat(quota.isExceeded()).isFalse();
    }

    @Test
    void 한도가_0이면_사용량0이어도_바로_초과다() {
        UsageQuota quota = UsageQuota.builder().user(user()).monthlyCostLimit(BigDecimal.ZERO).build();

        assertThat(quota.isExceeded()).isTrue(); // currentUsage(0) >= monthlyCostLimit(0)
    }

    @Test
    void 생성_직후에는_resetAt이_다음달_1일이라_리셋대상이_아니다() {
        UsageQuota quota = UsageQuota.builder().user(user()).monthlyCostLimit(BigDecimal.TEN).build();

        assertThat(quota.isResetDue()).isFalse();
    }

    @Test
    void reset하면_사용량이_0으로_초기화되고_다음_리셋일이_다음달_1일로_갱신된다() {
        UsageQuota quota = UsageQuota.builder().user(user()).monthlyCostLimit(BigDecimal.TEN).build();

        quota.reset();

        assertThat(quota.getCurrentUsage()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(quota.getResetAt()).isEqualTo(LocalDate.now().plusMonths(1).withDayOfMonth(1));
    }

    @Test
    void updateLimit으로_관리자가_한도를_변경할_수_있다() {
        UsageQuota quota = UsageQuota.builder().user(user()).monthlyCostLimit(BigDecimal.TEN).build();

        quota.updateLimit(BigDecimal.valueOf(50));

        assertThat(quota.getMonthlyCostLimit()).isEqualByComparingTo(BigDecimal.valueOf(50));
    }
}
