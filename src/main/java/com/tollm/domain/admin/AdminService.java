package com.tollm.domain.admin;

import com.tollm.domain.usage.RequestLogRepository;
import com.tollm.domain.usage.UsageQuota;
import com.tollm.domain.usage.UsageQuotaRepository;
import com.tollm.domain.usage.dto.AdminUsageSummaryResponse;
import com.tollm.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 권한 검증(ADMIN 전용)은 JwtAuthFilter가 /admin/** 진입 시점에 이미 처리한다 (컨트롤러 밖 단계).
// 여기서 role을 다시 검사하지 않는 이유: 인가 로직을 두 곳에 두면 한쪽만 고치고 다른 쪽을
// 잊었을 때 구멍이 생긴다 - 단일 지점(필터)에서만 검증하고, 이 사실은 테스트로 고정한다.
@Service
@RequiredArgsConstructor
public class AdminService {

    private final RequestLogRepository requestLogRepository;
    private final UsageQuotaRepository usageQuotaRepository;

    @Transactional(readOnly = true)
    public AdminUsageSummaryResponse allUsage(LocalDateTime from, LocalDateTime to) {
        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusMonths(1);
        LocalDateTime effectiveTo = to != null ? to : LocalDateTime.now();
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw ApiException.badRequest("from은 to보다 이전이어야 합니다");
        }
        return requestLogRepository.aggregateAll(effectiveFrom, effectiveTo);
    }

    @Transactional
    public void setQuota(Long userId, BigDecimal monthlyCostLimit) {
        UsageQuota quota = usageQuotaRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("사용자의 쿼터 정보를 찾을 수 없습니다"));
        quota.updateLimit(monthlyCostLimit); // save() 호출 없이 dirty checking으로 커밋 시 UPDATE (ApiKeyService.revoke와 동일 패턴)
    }
}
