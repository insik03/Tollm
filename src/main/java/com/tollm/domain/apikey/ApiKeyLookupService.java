package com.tollm.domain.apikey;

import com.tollm.global.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

// [성능 수정] ApiKeyAuthFilter가 매 /v1 요청마다 하던 키 해시 조회를 캐시로 감싼다.
// 필터가 리포지토리를 직접 부르면 캐시를 끼울 수 없어(프록시 밖), 조회 + "필요한 식별자만 추출"을
// 이 서비스로 옮기고 @Cacheable을 건다. 캐시에는 엔티티가 아니라 ApiKeyPrincipal(record)만 담아
// 세션 종료 후 지연 로딩 문제를 원천 차단한다.
@Service
@RequiredArgsConstructor
public class ApiKeyLookupService {

    private final ApiKeyRepository apiKeyRepository;

    // unless: 인증 실패(빈 Optional)는 캐시하지 않는다 - 잘못된/폐기된 키를 30초간 "확정 실패"로
    // 굳혀두면, 폐기 직후 재발급 같은 상황에서 불필요하게 막힐 수 있어서다. 성공만 캐시한다.
    // 주의: Spring Cache는 Optional 반환값을 언랩하므로 unless의 #result는 ApiKeyPrincipal(또는 null)이다.
    @Cacheable(cacheNames = CacheConfig.API_KEY_AUTH, key = "#keyHash", unless = "#result == null")
    @Transactional(readOnly = true)
    public Optional<ApiKeyPrincipal> authenticate(String keyHash) {
        return apiKeyRepository.findByKeyHashAndStatus(keyHash, ApiKey.Status.ACTIVE)
                .map(key -> new ApiKeyPrincipal(
                        key.getUser().getId(),
                        key.isTeamKey() ? key.getTeam().getId() : null, // 개인 키는 팀 없음 → null
                        key.getId()));
    }
}
