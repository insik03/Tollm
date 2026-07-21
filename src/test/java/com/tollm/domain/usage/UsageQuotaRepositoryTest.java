package com.tollm.domain.usage;

import com.tollm.domain.user.Role;
import com.tollm.domain.user.User;
import com.tollm.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// @DataJpaTest는 MySQL 대신 H2 임베디드 DB로 자동 대체된다(기본 동작) - 집계/누적 JPQL은
// 표준 문법이라 MySQL 전용 기능에 기대지 않으므로 docker 없이도 이 리포지토리 계층을 검증할 수 있다.
//
// @Transactional(NOT_SUPPORTED)인 이유: @DataJpaTest 기본값은 테스트 메서드 전체를 하나의
// 트랜잭션으로 감쌌다가 끝나면 롤백한다. 이 테스트는 여러 스레드(=여러 커넥션)가 동시에
// addUsage()를 호출하는 "진짜 동시성"을 검증해야 하는데, 기본 트랜잭션 안에서 준비(setup)한
// User/UsageQuota 행은 아직 커밋되지 않은 상태라 다른 커넥션에서는 격리 수준상 보이지 않는다
// (dirty read 불가). 그래서 이 테스트만 트랜잭션 래핑을 끄고 각 단계가 즉시 커밋되게 한다.
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UsageQuotaRepositoryTest {

    @Autowired
    private UsageQuotaRepository usageQuotaRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void addUsage_동시_요청_20개여도_lost_update_없이_전부_반영된다() throws InterruptedException {
        User user = userRepository.save(User.builder()
                .email("concurrent@test.com")
                .password("hash")
                .role(Role.MEMBER)
                .build());
        usageQuotaRepository.save(UsageQuota.builder()
                .user(user)
                .monthlyCostLimit(BigDecimal.valueOf(1000))
                .build());

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    usageQuotaRepository.addUsage(user.getId(), BigDecimal.ONE);
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(completed).isTrue();

        UsageQuota result = usageQuotaRepository.findByUserId(user.getId()).orElseThrow();
        // "읽고-더하고-저장" 방식이었다면 동시 요청 중 일부가 유실돼 20보다 작게 나올 수 있다.
        // bulk UPDATE(DB 레벨 원자적 갱신)라면 정확히 20이 나와야 한다.
        assertThat(result.getCurrentUsage()).isEqualByComparingTo(BigDecimal.valueOf(20));
    }

    @Test
    void addUsage는_다른_사용자의_쿼터에는_영향을_주지_않는다() {
        User u1 = userRepository.save(User.builder().email("q1@test.com").password("h").role(Role.MEMBER).build());
        User u2 = userRepository.save(User.builder().email("q2@test.com").password("h").role(Role.MEMBER).build());
        usageQuotaRepository.save(UsageQuota.builder().user(u1).monthlyCostLimit(BigDecimal.TEN).build());
        usageQuotaRepository.save(UsageQuota.builder().user(u2).monthlyCostLimit(BigDecimal.TEN).build());

        usageQuotaRepository.addUsage(u1.getId(), BigDecimal.valueOf(5));

        assertThat(usageQuotaRepository.findByUserId(u1.getId()).orElseThrow().getCurrentUsage())
                .isEqualByComparingTo(BigDecimal.valueOf(5));
        assertThat(usageQuotaRepository.findByUserId(u2.getId()).orElseThrow().getCurrentUsage())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
