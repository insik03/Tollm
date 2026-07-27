package com.tollm.domain.providerkey;

import com.tollm.domain.providerkey.dto.ProviderKeySummary;
import com.tollm.domain.user.User;
import com.tollm.domain.user.UserRepository;
import com.tollm.global.auth.CryptoService;
import com.tollm.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

// [BYOK] 사용자 프로바이더 키 등록/조회/삭제 + 프록시가 쓸 복호화 조회.
// 원문 키는 등록 시점에만 받아 즉시 암호화하고, 이후 어떤 조회 API로도 원문을 돌려주지 않는다.
@Service
@RequiredArgsConstructor
public class ProviderKeyService {

    // 우리가 라우팅을 지원하는 프로바이더만 등록 허용 (ProviderRouter와 일치)
    private static final Set<String> SUPPORTED = Set.of("openai", "anthropic");

    private final ProviderCredentialRepository repository;
    private final UserRepository userRepository;
    private final CryptoService cryptoService;

    @Transactional
    public ProviderKeySummary register(Long userId, String provider, String rawKey) {
        String normalized = normalizeProvider(provider);
        String key = rawKey == null ? "" : rawKey.trim();
        if (key.isEmpty()) {
            throw ApiException.badRequest("apiKey가 비어 있습니다");
        }
        String encrypted = cryptoService.encrypt(key);
        String preview = mask(key);

        // 이미 있으면 교체(update), 없으면 새로 생성 - (user, provider)당 1개 유지
        ProviderCredential cred = repository.findByUserIdAndProvider(userId, normalized)
                .map(existing -> {
                    existing.replace(encrypted, preview);
                    return existing;
                })
                .orElseGet(() -> {
                    User user = userRepository.getReferenceById(userId); // FK만 필요
                    return repository.save(ProviderCredential.builder()
                            .user(user).provider(normalized)
                            .encryptedKey(encrypted).keyPreview(preview)
                            .build());
                });
        return ProviderKeySummary.from(cred);
    }

    @Transactional(readOnly = true)
    public List<ProviderKeySummary> myKeys(Long userId) {
        return repository.findByUserId(userId).stream().map(ProviderKeySummary::from).toList();
    }

    @Transactional
    public void delete(Long userId, String provider) {
        String normalized = normalizeProvider(provider);
        ProviderCredential cred = repository.findByUserIdAndProvider(userId, normalized)
                .orElseThrow(() -> ApiException.notFound("등록된 키가 없습니다"));
        repository.delete(cred);
    }

    // 프록시가 요청 처리 중 호출: 이 사용자가 해당 프로바이더 키를 등록했으면 복호화해서 반환.
    // 없으면 Optional.empty() → 호출부(ProxyService)가 400으로 막고 "등록하라"고 안내한다(서버키 폴백 없음).
    @Transactional(readOnly = true)
    public Optional<String> decryptedKeyFor(Long userId, String provider) {
        return repository.findByUserIdAndProvider(userId, provider)
                .map(c -> cryptoService.decrypt(c.getEncryptedKey()));
    }

    private String normalizeProvider(String provider) {
        String p = provider == null ? "" : provider.trim().toLowerCase();
        if (!SUPPORTED.contains(p)) {
            throw ApiException.badRequest("지원하지 않는 프로바이더입니다: " + provider + " (openai, anthropic만 가능)");
        }
        return p;
    }

    // 앞 8자 + "..." + 뒤 4자만 남긴다. 너무 짧으면 앞부분만 노출하고 뒤는 가린다.
    private String mask(String key) {
        if (key.length() <= 12) {
            return (key.isEmpty() ? "" : key.substring(0, Math.min(3, key.length()))) + "...";
        }
        return key.substring(0, 8) + "..." + key.substring(key.length() - 4);
    }
}
