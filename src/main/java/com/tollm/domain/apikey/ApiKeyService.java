package com.tollm.domain.apikey;

import com.tollm.domain.apikey.dto.ApiKeyIssueResponse;
import com.tollm.domain.apikey.dto.ApiKeySummary;
import com.tollm.domain.user.User;
import com.tollm.domain.user.UserRepository;
import com.tollm.global.auth.HashUtils;
import com.tollm.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final UserRepository userRepository;

    // 일반 Random은 예측 가능해서 보안 용도 금지. SecureRandom은 OS의 암호학적 난수원 사용
    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public ApiKeyIssueResponse issue(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다"));

        byte[] bytes = new byte[32]; // 256비트 엔트로피 → 무차별 대입 불가능
        RANDOM.nextBytes(bytes);
        String rawKey = "tlm_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String prefix = rawKey.substring(0, 8); // 목록에서 "어느 키인지" 식별용 (tlm_xxxx)

        ApiKey saved = apiKeyRepository.save(ApiKey.builder()
                .user(user)
                .keyHash(HashUtils.sha256(rawKey)) // 원문은 저장하지 않는다
                .prefix(prefix)
                .build());

        return new ApiKeyIssueResponse(saved.getId(), rawKey, prefix); // 원문 노출은 이 응답이 처음이자 마지막
    }

    @Transactional(readOnly = true)
    public List<ApiKeySummary> myKeys(Long userId) {
        return apiKeyRepository.findByUserId(userId).stream()
                .map(k -> new ApiKeySummary(k.getId(), k.getPrefix(), k.getStatus().name(), k.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void revoke(Long userId, Long keyId) {
        ApiKey key = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> ApiException.notFound("키를 찾을 수 없습니다"));
        if (!key.getUser().getId().equals(userId)) {
            throw ApiException.forbidden("본인의 키만 폐기할 수 있습니다");
        }
        key.revoke(); // save() 호출 없음 — JPA 변경 감지(dirty checking)가 커밋 시점에 UPDATE 실행
    }
}
