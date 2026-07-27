package com.tollm.domain.providerkey;

import com.tollm.domain.providerkey.dto.ProviderKeySummary;
import com.tollm.domain.team.Team;
import com.tollm.domain.team.TeamMember;
import com.tollm.domain.team.TeamMemberRepository;
import com.tollm.domain.team.TeamRepository;
import com.tollm.domain.user.User;
import com.tollm.domain.user.UserRepository;
import com.tollm.global.auth.CryptoService;
import com.tollm.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// [BYOK] 사용자/팀 프로바이더 키 등록/조회/삭제 + 프록시가 쓸 복호화 조회.
// 원문 키는 등록 시점에만 받아 즉시 암호화하고, 이후 어떤 조회 API로도 원문을 돌려주지 않는다.
@Service
@RequiredArgsConstructor
public class ProviderKeyService {

    private final ProviderCredentialRepository repository;
    private final UserRepository userRepository;
    private final CryptoService cryptoService;
    // 팀 BYOK(add-on) - 개인 경로는 위 3개만으로 동작
    private final TeamProviderCredentialRepository teamProviderCredentialRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;

    @Transactional
    public ProviderKeySummary register(Long userId, String provider, String rawKey, String label) {
        String normalized = normalizeProvider(provider);
        String key = rawKey == null ? "" : rawKey.trim();
        if (key.isEmpty()) {
            throw ApiException.badRequest("apiKey가 비어 있습니다");
        }
        String encrypted = cryptoService.encrypt(key);
        String preview = mask(key);
        String cleanLabel = (label == null || label.isBlank()) ? null : label.trim();

        // 이미 있으면 교체(update), 없으면 새로 생성 - (user, provider)당 1개 유지
        ProviderCredential cred = repository.findByUserIdAndProvider(userId, normalized)
                .map(existing -> {
                    existing.replace(encrypted, preview, cleanLabel);
                    return existing;
                })
                .orElseGet(() -> {
                    User user = userRepository.getReferenceById(userId); // FK만 필요
                    return repository.save(ProviderCredential.builder()
                            .user(user).provider(normalized)
                            .encryptedKey(encrypted).keyPreview(preview).label(cleanLabel)
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

    // ---- 팀 BYOK (add-on) ----
    // 팀 요청은 서버 공용키가 아니라 "팀이 등록한 키"로 나간다(검수 #9 근본 해결).
    // [phase2 검수] 팀 공용 키(=팀 과금이 걸리는 민감 자원)의 등록/교체/삭제는 OWNER만 할 수 있게 한다.
    // 그렇지 않으면 아무 멤버나 팀 키를 지워 팀 전체 프록시를 막거나(DoS), 자기 키로 덮어써 과금을
    // 가로챌 수 있다. 조회(teamKeys)는 마스킹만 보여주므로 팀원 누구나 허용.

    @Transactional
    public ProviderKeySummary registerTeamKey(Long userId, Long teamId, String provider, String rawKey) {
        requireOwner(teamId, userId);
        String normalized = normalizeProvider(provider);
        String key = rawKey == null ? "" : rawKey.trim();
        if (key.isEmpty()) {
            throw ApiException.badRequest("apiKey가 비어 있습니다");
        }
        String encrypted = cryptoService.encrypt(key);
        String preview = mask(key);

        TeamProviderCredential cred = teamProviderCredentialRepository.findByTeamIdAndProvider(teamId, normalized)
                .map(existing -> {
                    existing.replace(encrypted, preview);
                    return existing;
                })
                .orElseGet(() -> {
                    Team team = teamRepository.getReferenceById(teamId);
                    return teamProviderCredentialRepository.save(TeamProviderCredential.builder()
                            .team(team).provider(normalized)
                            .encryptedKey(encrypted).keyPreview(preview)
                            .build());
                });
        return new ProviderKeySummary(cred.getProvider(), null, cred.getKeyPreview(), cred.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<ProviderKeySummary> teamKeys(Long userId, Long teamId) {
        requireMember(teamId, userId);
        return teamProviderCredentialRepository.findByTeamId(teamId).stream()
                .map(c -> new ProviderKeySummary(c.getProvider(), null, c.getKeyPreview(), c.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void deleteTeamKey(Long userId, Long teamId, String provider) {
        requireOwner(teamId, userId);
        String normalized = normalizeProvider(provider);
        TeamProviderCredential cred = teamProviderCredentialRepository.findByTeamIdAndProvider(teamId, normalized)
                .orElseThrow(() -> ApiException.notFound("등록된 팀 키가 없습니다"));
        teamProviderCredentialRepository.delete(cred);
    }

    // 프록시가 팀 요청 처리 중 호출 - 팀이 등록한 키를 복호화. 없으면 empty → 호출부가 400으로 막는다.
    @Transactional(readOnly = true)
    public Optional<String> decryptedTeamKeyFor(Long teamId, String provider) {
        return teamProviderCredentialRepository.findByTeamIdAndProvider(teamId, provider)
                .map(c -> cryptoService.decrypt(c.getEncryptedKey()));
    }

    // 팀원 여부 검증 (ApiKeyService/TeamService의 requireMember와 같은 규칙)
    private void requireMember(Long teamId, Long userId) {
        teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> ApiException.forbidden("팀 멤버만 접근할 수 있습니다"));
    }

    // 팀 OWNER 여부 검증 - 팀 공용 키 등록/삭제 같은 민감 작업 전용
    private void requireOwner(Long teamId, Long userId) {
        TeamMember member = teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> ApiException.forbidden("팀 멤버만 접근할 수 있습니다"));
        if (member.getTeamRole() != TeamMember.TeamRole.OWNER) {
            throw ApiException.forbidden("팀 OWNER만 팀 LLM 키를 관리할 수 있습니다");
        }
    }

    // 프로바이더 이름을 자유 입력으로 허용한다(openai/anthropic 외 kimi 등도 등록 가능).
    // 단, 실제 프록시 라우팅(ProviderRouter)은 아직 openai/anthropic만 지원하므로 그 외 프로바이더로
    // 등록한 키는 저장은 되지만 채팅 요청엔 아직 쓰이지 않는다(라우팅 확장은 추후).
    private String normalizeProvider(String provider) {
        String p = provider == null ? "" : provider.trim().toLowerCase();
        if (p.isEmpty()) {
            throw ApiException.badRequest("provider는 필수입니다");
        }
        if (p.length() > 50) {
            throw ApiException.badRequest("provider 이름이 너무 깁니다");
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
