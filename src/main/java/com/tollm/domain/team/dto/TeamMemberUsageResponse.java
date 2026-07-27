package com.tollm.domain.team.dto;

import java.math.BigDecimal;

// 팀 멤버 한 명의 사용량. displayName은 닉네임(없으면 이메일).
public record TeamMemberUsageResponse(Long userId, String displayName,
                                      BigDecimal totalCost, Long totalTokens, Long requestCount) {
}
