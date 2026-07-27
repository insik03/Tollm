package com.tollm.domain.apikey;

import com.tollm.domain.apikey.dto.ApiKeyIssueResponse;
import com.tollm.domain.apikey.dto.ApiKeyLimitResponse;
import com.tollm.domain.apikey.dto.ApiKeySummary;
import com.tollm.domain.team.Team;
import com.tollm.domain.team.TeamMember;
import com.tollm.domain.team.TeamMemberRepository;
import com.tollm.domain.team.TeamRepository;
import com.tollm.domain.usage.RequestLogRepository;
import com.tollm.domain.usage.dto.UsageSummaryResponse;
import com.tollm.domain.user.User;
import com.tollm.domain.user.UserRepository;
import com.tollm.global.auth.HashUtils;
import com.tollm.global.config.CacheConfig;
import com.tollm.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final RequestLogRepository requestLogRepository;

    // 일반 Random은 예측 가능해서 보안 용도 금지. SecureRandom은 OS의 암호학적 난수원 사용
    private static final SecureRandom RANDOM = new SecureRandom();

    // [봇 방어] 한 사용자/팀이 무제한으로 키를 발급해 DB를 부풀리는 걸 막는 활성 키 상한.
    // 정상 사용엔 넉넉하지만 대량 발급 공격은 차단한다. (향후 요금제 도입 시 플랜별로 분기 가능)
    private static final long MAX_ACTIVE_KEYS = 20;

    @Transactional
    public ApiKeyIssueResponse issue(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다"));
        if (apiKeyRepository.countByUserIdAndTeamIsNullAndStatus(userId, ApiKey.Status.ACTIVE) >= MAX_ACTIVE_KEYS) {
            throw ApiException.badRequest("발급 가능한 활성 키 수(" + MAX_ACTIVE_KEYS + "개)를 초과했습니다. 안 쓰는 키를 폐기 후 다시 시도하세요");
        }
        return issueInternal(user, null);
    }

    @Transactional(readOnly = true)
    public List<ApiKeySummary> myKeys(Long userId) {
        return toSummaries(apiKeyRepository.findByUserId(userId));
    }

    // 활성 키 상한 안내 (대시보드 [N/최대] 표시용)
    @Transactional(readOnly = true)
    public ApiKeyLimitResponse keyLimit(Long userId) {
        long active = apiKeyRepository.countByUserIdAndTeamIsNullAndStatus(userId, ApiKey.Status.ACTIVE);
        return new ApiKeyLimitResponse(MAX_ACTIVE_KEYS, active, Math.max(0, MAX_ACTIVE_KEYS - active));
    }

    @Transactional(readOnly = true)
    public ApiKeyLimitResponse teamKeyLimit(Long userId, Long teamId) {
        requireMember(teamId, userId);
        long active = apiKeyRepository.countByTeamIdAndStatus(teamId, ApiKey.Status.ACTIVE);
        return new ApiKeyLimitResponse(MAX_ACTIVE_KEYS, active, Math.max(0, MAX_ACTIVE_KEYS - active));
    }

    // @CacheEvict: 폐기한 키가 인증 캐시(TTL 30초)에 남아 최대 30초간 계속 통과하는 걸 막기 위해
    // 폐기 시 인증 캐시를 통째로 비운다. 폐기는 드문 이벤트라 전체 무효화 비용은 무시할 만하다.
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.API_KEY_AUTH, allEntries = true)
    public void revoke(Long userId, Long keyId) {
        ApiKey key = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> ApiException.notFound("키를 찾을 수 없습니다"));
        if (!key.getUser().getId().equals(userId)) {
            throw ApiException.forbidden("본인의 키만 폐기할 수 있습니다");
        }
        key.revoke(); // save() 호출 없음 — JPA 변경 감지(dirty checking)가 커밋 시점에 UPDATE 실행
    }

    // ---- 팀 API 키 (add-on) ----
    // 팀 소속 여부 검증만 하고, 나머지 발급 로직(SecureRandom 생성/해싱/원문 1회 노출)은
    // 개인 키와 완전히 동일한 issueInternal()을 그대로 재사용한다.

    @Transactional
    public ApiKeyIssueResponse issueForTeam(Long userId, Long teamId) {
        requireMember(teamId, userId); // 팀원이면 누구나 팀 키를 발급할 수 있게 허용
        if (apiKeyRepository.countByTeamIdAndStatus(teamId, ApiKey.Status.ACTIVE) >= MAX_ACTIVE_KEYS) {
            throw ApiException.badRequest("팀의 활성 키 수(" + MAX_ACTIVE_KEYS + "개)를 초과했습니다. 안 쓰는 키를 폐기 후 다시 시도하세요");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다"));
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> ApiException.notFound("팀을 찾을 수 없습니다"));
        return issueInternal(user, team);
    }

    // 팀 키 목록: 각 키를 "누가 발급했는지"(발급자)를 함께 준다. 발급자 표시는 팀 멤버가 설정한
    // 닉네임을 우선하고, 없으면 이메일로 대체한다(멤버별 사용량 표시와 동일한 규칙).
    @Transactional(readOnly = true)
    public List<ApiKeySummary> teamKeys(Long userId, Long teamId) {
        requireMember(teamId, userId);
        Map<Long, String> nicknameByUser = teamMemberRepository.findByTeamId(teamId).stream()
                .filter(m -> m.getNickname() != null && !m.getNickname().isBlank())
                .collect(Collectors.toMap(m -> m.getUser().getId(), TeamMember::getNickname, (a, b) -> a));
        return apiKeyRepository.findByTeamId(teamId).stream()
                .map(k -> {
                    String issuer = nicknameByUser.getOrDefault(k.getUser().getId(), k.getUser().getEmail());
                    return new ApiKeySummary(k.getId(), k.getPrefix(), k.getStatus().name(), issuer, k.getCreatedAt());
                })
                .toList();
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.API_KEY_AUTH, allEntries = true) // 팀 키 폐기도 인증 캐시 무효화 (revoke와 동일 이유)
    public void revokeTeamKey(Long userId, Long teamId, Long keyId) {
        TeamMember member = requireMember(teamId, userId);
        ApiKey key = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> ApiException.notFound("키를 찾을 수 없습니다"));
        if (key.getTeam() == null || !key.getTeam().getId().equals(teamId)) {
            throw ApiException.notFound("해당 팀의 키가 아닙니다");
        }
        // 키를 발급한 본인이거나, 팀 OWNER면 폐기 가능 (다른 멤버가 마음대로 못 지우게)
        boolean isIssuer = key.getUser().getId().equals(userId);
        boolean isOwner = member.getTeamRole() == TeamMember.TeamRole.OWNER;
        if (!isIssuer && !isOwner) {
            throw ApiException.forbidden("키를 발급한 본인 또는 팀 OWNER만 폐기할 수 있습니다");
        }
        key.revoke();
    }

    // ---- 키 단위 사용량 (add-on) ----
    // "userId/teamId 합산"이 아니라 "이 키 하나로 얼마나 썼는지"를 구분해서 보고 싶다는 요구로 추가.
    // 같은 사람이 개인 키를 여러 개 발급받아도 서로 안 섞이고, 팀 키 사용은 애초에 별도 team_id가
    // 찍혀서(RequestLogRepository.aggregateByUser의 team IS NULL 조건) 개인 합산에 새지 않는다 -
    // 이 메서드는 그 위에서 "키 하나" 단위까지 더 잘게 쪼개 보여준다.

    @Transactional(readOnly = true)
    public UsageSummaryResponse keyUsage(Long userId, Long keyId, LocalDateTime from, LocalDateTime to) {
        ApiKey key = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> ApiException.notFound("키를 찾을 수 없습니다"));
        if (key.isTeamKey() || !key.getUser().getId().equals(userId)) {
            throw ApiException.forbidden("본인의 개인 키만 조회할 수 있습니다");
        }
        return aggregateKeyUsage(keyId, from, to);
    }

    @Transactional(readOnly = true)
    public UsageSummaryResponse teamKeyUsage(Long userId, Long teamId, Long keyId, LocalDateTime from, LocalDateTime to) {
        requireMember(teamId, userId);
        ApiKey key = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> ApiException.notFound("키를 찾을 수 없습니다"));
        if (key.getTeam() == null || !key.getTeam().getId().equals(teamId)) {
            throw ApiException.notFound("해당 팀의 키가 아닙니다");
        }
        return aggregateKeyUsage(keyId, from, to);
    }

    // UsageService.myUsage/AdminService.allUsage와 같은 "from/to 기본값 + 역전 검증" 패턴
    private UsageSummaryResponse aggregateKeyUsage(Long keyId, LocalDateTime from, LocalDateTime to) {
        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusMonths(1);
        LocalDateTime effectiveTo = to != null ? to : LocalDateTime.now();
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw ApiException.badRequest("from은 to보다 이전이어야 합니다");
        }
        return requestLogRepository.aggregateByApiKey(keyId, effectiveFrom, effectiveTo);
    }

    private ApiKeyIssueResponse issueInternal(User user, Team team) {
        byte[] bytes = new byte[32]; // 256비트 엔트로피 → 무차별 대입 불가능
        RANDOM.nextBytes(bytes);
        String rawKey = "tlm_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String prefix = rawKey.substring(0, 8); // 목록에서 "어느 키인지" 식별용 (tlm_xxxx)

        ApiKey saved = apiKeyRepository.save(ApiKey.builder()
                .user(user)
                .team(team) // null이면 기존과 동일한 개인 키
                .keyHash(HashUtils.sha256(rawKey)) // 원문은 저장하지 않는다
                .prefix(prefix)
                .build());

        return new ApiKeyIssueResponse(saved.getId(), rawKey, prefix); // 원문 노출은 이 응답이 처음이자 마지막
    }

    // 개인 키 목록: 발급자는 본인이라 표시 불필요 → issuer=null
    private List<ApiKeySummary> toSummaries(List<ApiKey> keys) {
        return keys.stream()
                .map(k -> new ApiKeySummary(k.getId(), k.getPrefix(), k.getStatus().name(), null, k.getCreatedAt()))
                .toList();
    }

    private TeamMember requireMember(Long teamId, Long userId) {
        return teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> ApiException.forbidden("팀 멤버만 접근할 수 있습니다"));
    }
}
