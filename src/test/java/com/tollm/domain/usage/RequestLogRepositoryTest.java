package com.tollm.domain.usage;

import com.tollm.domain.usage.dto.AdminUsageSummaryResponse;
import com.tollm.domain.usage.dto.UsageSummaryResponse;
import com.tollm.domain.user.Role;
import com.tollm.domain.user.User;
import com.tollm.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// @DataJpaTest -> H2 임베디드 DB로 자동 대체. 집계는 표준 JPQL(SUM/COUNT/COALESCE)이라
// MySQL 전용 문법에 기대지 않으므로 docker 없이도 검증 가능하다.
@DataJpaTest
class RequestLogRepositoryTest {

    @Autowired
    private RequestLogRepository requestLogRepository;

    @Autowired
    private UserRepository userRepository;

    private User saveUser(String email) {
        return userRepository.save(User.builder().email(email).password("hash").role(Role.MEMBER).build());
    }

    private RequestLog log(User user, int inputTokens, int outputTokens, BigDecimal cost, boolean cacheHit) {
        return RequestLog.builder()
                .user(user).model("gpt-4o-mini").providerName("openai")
                .inputTokens(inputTokens).outputTokens(outputTokens).cost(cost)
                .latencyMs(100L).statusCode(200).cacheHit(cacheHit)
                .build();
    }

    @Test
    void 로그가_없으면_집계는_0으로_반환된다_null이_아니다() {
        User user = saveUser("empty@test.com");

        UsageSummaryResponse summary = requestLogRepository.aggregateByUser(
                user.getId(), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(summary.totalCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.totalTokens()).isZero();
        assertThat(summary.requestCount()).isZero();
        assertThat(summary.cacheHitCount()).isZero();
    }

    @Test
    void 사용자별_기간_집계가_비용_토큰_캐시히트를_정확히_합산한다() {
        User user = saveUser("agg@test.com");
        requestLogRepository.save(log(user, 100, 50, BigDecimal.valueOf(0.05), false));
        requestLogRepository.save(log(user, 0, 0, BigDecimal.ZERO, true));

        UsageSummaryResponse summary = requestLogRepository.aggregateByUser(
                user.getId(), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(summary.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(0.05));
        assertThat(summary.totalTokens()).isEqualTo(150L);
        assertThat(summary.requestCount()).isEqualTo(2L);
        assertThat(summary.cacheHitCount()).isEqualTo(1L);
        assertThat(summary.cacheHitRate()).isEqualTo(0.5);
    }

    @Test
    void 기간_범위_밖의_로그는_집계에서_제외된다() {
        User user = saveUser("range@test.com");
        RequestLog outOfRange = log(user, 10, 10, BigDecimal.ONE, false);
        requestLogRepository.save(outOfRange);

        UsageSummaryResponse summary = requestLogRepository.aggregateByUser(
                user.getId(), LocalDateTime.now().plusDays(10), LocalDateTime.now().plusDays(20));

        assertThat(summary.requestCount()).isZero();
    }

    @Test
    void 다른_사용자의_로그는_집계에_섞이지_않는다() {
        User me = saveUser("me@test.com");
        User other = saveUser("other@test.com");
        requestLogRepository.save(log(me, 10, 10, BigDecimal.ONE, false));
        requestLogRepository.save(log(other, 100, 100, BigDecimal.TEN, false));

        UsageSummaryResponse summary = requestLogRepository.aggregateByUser(
                me.getId(), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(summary.requestCount()).isEqualTo(1L);
        assertThat(summary.totalCost()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void 전체_집계는_사용자_구분없이_합산하고_활동사용자수를_센다() {
        User u1 = saveUser("all1@test.com");
        User u2 = saveUser("all2@test.com");
        requestLogRepository.save(log(u1, 10, 10, BigDecimal.ONE, false));
        requestLogRepository.save(log(u2, 10, 10, BigDecimal.ONE, false));

        AdminUsageSummaryResponse summary = requestLogRepository.aggregateAll(
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(summary.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(2));
        assertThat(summary.requestCount()).isEqualTo(2L);
        assertThat(summary.activeUserCount()).isEqualTo(2L);
    }

    @Test
    void 본인_로그_페이징은_최신순으로_정렬된다() {
        User user = saveUser("paging@test.com");
        requestLogRepository.save(log(user, 1, 1, BigDecimal.ONE, false));
        requestLogRepository.save(log(user, 2, 2, BigDecimal.ONE, false));

        var page = requestLogRepository.findByUserIdOrderByCreatedAtDesc(
                user.getId(), org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).getCreatedAt())
                .isAfterOrEqualTo(page.getContent().get(1).getCreatedAt());
    }
}
