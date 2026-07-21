package com.tollm.domain.usage;

import com.tollm.domain.usage.dto.RequestLogResponse;
import com.tollm.domain.usage.dto.UsageSummaryResponse;
import com.tollm.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UsageService {

    private final RequestLogRepository requestLogRepository;

    @Transactional(readOnly = true)
    public UsageSummaryResponse myUsage(Long userId, LocalDateTime from, LocalDateTime to) {
        // from/to 미지정 시 최근 1개월로 기본 조회 - 매번 범위를 강제하지 않아 사용성을 확보한다
        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusMonths(1);
        LocalDateTime effectiveTo = to != null ? to : LocalDateTime.now();
        validateRange(effectiveFrom, effectiveTo);
        return requestLogRepository.aggregateByUser(userId, effectiveFrom, effectiveTo);
    }

    @Transactional(readOnly = true)
    public Page<RequestLogResponse> myLogs(Long userId, Pageable pageable) {
        return requestLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(RequestLogResponse::from);
    }

    private void validateRange(LocalDateTime from, LocalDateTime to) {
        if (from.isAfter(to)) {
            throw ApiException.badRequest("from은 to보다 이전이어야 합니다");
        }
    }
}
