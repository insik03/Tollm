package com.tollm.domain.usage;

import com.tollm.domain.apikey.ApiKey;
import com.tollm.domain.apikey.ApiKeyRepository;
import com.tollm.domain.team.Team;
import com.tollm.domain.team.TeamRepository;
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

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

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

    // [버그 회귀] 팀 키로 쓴 사용량이 그 사람의 개인 /usage/me에 새던 문제를 잡는 테스트.
    // RequestLog.user는 팀 키 요청에서도 "발급한 사람"으로 채워지므로, l.team IS NULL 조건이
    // 없으면 이 테스트는 requestCount=2로 실패한다(팀 로그까지 합산됨) - 실사용 중 발견된 버그.
    @Test
    void 팀_키_사용량은_본인의_개인_집계에_섞이지_않는다() {
        User user = saveUser("leak-check@test.com");
        RequestLog personalLog = log(user, 10, 10, BigDecimal.ONE, false); // team 없음
        requestLogRepository.save(personalLog);

        Team team = teamRepository.save(Team.builder().name("팀").build());
        RequestLog teamLog = RequestLog.builder()
                .user(user).team(team).model("gpt-4o-mini").providerName("openai")
                .inputTokens(999).outputTokens(999).cost(BigDecimal.valueOf(99))
                .latencyMs(100L).statusCode(200).cacheHit(false).build();
        requestLogRepository.save(teamLog);

        UsageSummaryResponse summary = requestLogRepository.aggregateByUser(
                user.getId(), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(summary.requestCount()).isEqualTo(1L); // 개인 로그 1건만 - 팀 로그(2번째)는 제외
        assertThat(summary.totalCost()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void 키_단위_집계는_같은_사용자의_다른_키_로그와_섞이지_않는다() {
        User user = saveUser("multikey@test.com");
        ApiKey keyA = apiKeyRepository.save(ApiKey.builder().user(user).keyHash("hashA").prefix("tlm_aaaa").build());
        ApiKey keyB = apiKeyRepository.save(ApiKey.builder().user(user).keyHash("hashB").prefix("tlm_bbbb").build());

        requestLogRepository.save(RequestLog.builder()
                .user(user).apiKey(keyA).model("gpt-4o-mini").providerName("openai")
                .inputTokens(10).outputTokens(10).cost(BigDecimal.ONE)
                .latencyMs(100L).statusCode(200).cacheHit(false).build());
        requestLogRepository.save(RequestLog.builder()
                .user(user).apiKey(keyB).model("gpt-4o-mini").providerName("openai")
                .inputTokens(50).outputTokens(50).cost(BigDecimal.TEN)
                .latencyMs(100L).statusCode(200).cacheHit(false).build());

        UsageSummaryResponse summaryA = requestLogRepository.aggregateByApiKey(
                keyA.getId(), LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(summaryA.requestCount()).isEqualTo(1L);
        assertThat(summaryA.totalCost()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(summaryA.totalTokens()).isEqualTo(20L);
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
