package com.tollm.domain.usage.dto;

import java.math.BigDecimal;

// RequestLog를 팀 내 사용자별로 집계한 원시 행 (JPQL 생성자 표현식 대상).
// email/nickname 같은 표시 정보는 TeamService가 팀 멤버 정보와 합쳐 채운다.
public record TeamMemberUsageRow(Long userId, BigDecimal totalCost, Long totalTokens, Long requestCount) {
}
