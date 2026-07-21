package com.tollm.domain.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

public interface UsageQuotaRepository extends JpaRepository<UsageQuota, Long> {

    Optional<UsageQuota> findByUserId(Long userId);

    // "읽고(findByUserId) - 더하고(currentUsage+cost) - 저장(save)" 방식은 동시 요청 시
    // 두 트랜잭션이 같은 스냅샷을 읽어 하나의 갱신이 사라지는 lost update를 유발한다.
    // JPQL bulk UPDATE는 "currentUsage = currentUsage + cost"를 DB가 한 번의 SQL로 원자적으로
    // 반영하므로(행 잠금은 DB 엔진이 처리) 애플리케이션 레벨에서 별도 락을 잡지 않아도 안전하다.
    // clearAutomatically = true: bulk 연산은 영속성 컨텍스트를 거치지 않으므로, 같은 트랜잭션
    // 안에서 이후에 이 엔티티를 다시 읽을 코드가 있다면 캐시된(stale) 값을 보지 않도록 정리한다.
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE UsageQuota q SET q.currentUsage = q.currentUsage + :cost WHERE q.user.id = :userId")
    int addUsage(@Param("userId") Long userId, @Param("cost") BigDecimal cost);
}
