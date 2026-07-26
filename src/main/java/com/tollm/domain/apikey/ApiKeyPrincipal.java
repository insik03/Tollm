package com.tollm.domain.apikey;

// API 키 인증에 성공한 요청의 신원 정보(캐시 대상). ApiKey "엔티티"를 캐시에 담으면 세션이 닫힌 뒤
// 지연 로딩 필드(user/team)를 건드릴 때 LazyInitializationException이 나므로, 필요한 식별자만 뽑아
// 불변 record로 캐시한다. teamId는 개인 키면 null.
public record ApiKeyPrincipal(Long userId, Long teamId, Long apiKeyId) {
}
