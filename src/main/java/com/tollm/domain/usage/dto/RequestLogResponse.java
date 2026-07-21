package com.tollm.domain.usage.dto;

import com.tollm.domain.usage.RequestLog;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 본인 로그 목록(/usage/me/logs) 응답. RequestLog 엔티티를 직접 반환하면 User(Lazy 연관)까지
// 직렬화 시도되며 예기치 않은 쿼리/순환참조/프록시 예외가 날 수 있어 필요한 필드만 옮겨 담는다.
public record RequestLogResponse(
        Long id,
        String model,
        String providerName,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal cost,
        Long latencyMs,
        Integer statusCode,
        boolean cacheHit,
        LocalDateTime createdAt
) {
    public static RequestLogResponse from(RequestLog log) {
        return new RequestLogResponse(
                log.getId(), log.getModel(), log.getProviderName(),
                log.getInputTokens(), log.getOutputTokens(), log.getCost(),
                log.getLatencyMs(), log.getStatusCode(), log.isCacheHit(), log.getCreatedAt());
    }
}
