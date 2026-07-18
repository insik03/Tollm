package com.tollm.domain.apikey.dto;

import java.time.LocalDateTime;

// 목록 조회용: 원문/해시는 절대 노출하지 않고 식별 정보만
public record ApiKeySummary(Long id, String prefix, String status, LocalDateTime createdAt) {
}
