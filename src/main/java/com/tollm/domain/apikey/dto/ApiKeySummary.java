package com.tollm.domain.apikey.dto;

import java.time.LocalDateTime;

// 목록 조회용: 원문/해시는 절대 노출하지 않고 식별 정보만.
// issuer: 이 키를 발급한 사람(팀 키 목록에서 "누가 발급했는지" 표시용, 닉네임 없으면 이메일).
//         개인 키 목록에서는 본인이므로 null로 둔다.
public record ApiKeySummary(Long id, String prefix, String status, String issuer, LocalDateTime createdAt) {
}
