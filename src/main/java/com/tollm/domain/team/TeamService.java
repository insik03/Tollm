package com.tollm.domain.team;

import com.tollm.domain.apikey.ApiKey;
import com.tollm.domain.apikey.ApiKeyRepository;
import com.tollm.domain.providerkey.TeamProviderCredentialRepository;
import com.tollm.domain.team.dto.*;
import com.tollm.domain.usage.RequestLogRepository;
import com.tollm.domain.usage.dto.TeamMemberUsageRow;
import com.tollm.domain.usage.dto.TeamUsageSummaryResponse;
import com.tollm.domain.user.User;
import com.tollm.domain.user.UserRepository;
import com.tollm.global.config.CacheConfig;
import com.tollm.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TeamService {

    // 팀 기능은 "사용자로 쓰다가 팀으로도 쓰고 싶은 사람"을 위한 추가(add-on) 기능이다.
    // 그래서 개인 인증/키/쿼터/레이트리밋 코드는 전혀 건드리지 않고, 같은 패턴을 팀 단위로
    // 병렬로 새로 둔다 (TeamUsageQuota가 UsageQuota를 복제한 것과 같은 원칙).
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamInviteRepository teamInviteRepository;
    private final TeamUsageQuotaRepository teamUsageQuotaRepository;
    private final RequestLogRepository requestLogRepository;
    private final UserRepository userRepository;
    // 탈퇴/강제퇴장/해지 시 연쇄 폐기용
    private final ApiKeyRepository apiKeyRepository;
    private final TeamProviderCredentialRepository teamProviderCredentialRepository;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int INVITE_VALID_DAYS = 7;

    @Transactional
    public TeamResponse createTeam(Long userId, CreateTeamRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다"));

        Team team = teamRepository.save(Team.builder().name(request.name()).build());

        // 만든 사람은 자동으로 OWNER (AuthController.signup이 가입 시 기본 쿼터를 만드는 것과 같은 이유로
        // "팀 생성"과 "OWNER 등록 + 쿼터 생성"을 하나의 트랜잭션으로 묶는다 - 부분 성공 방지)
        teamMemberRepository.save(TeamMember.builder()
                .team(team).user(user).teamRole(TeamMember.TeamRole.OWNER).build());
        teamUsageQuotaRepository.save(TeamUsageQuota.builder()
                .team(team).monthlyCostLimit(request.monthlyBudget()).build());

        return TeamResponse.from(team);
    }

    @Transactional(readOnly = true)
    public List<MyTeamResponse> myTeams(Long userId) {
        return teamMemberRepository.findByUserId(userId).stream()
                .map(MyTeamResponse::from)
                .toList();
    }

    @Transactional
    public TeamInviteResponse createInvite(Long userId, Long teamId) {
        requireOwner(teamId, userId);
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> ApiException.notFound("팀을 찾을 수 없습니다"));

        byte[] bytes = new byte[24]; // API 키만큼 민감하지는 않지만(TeamInvite 주석 참고) 여전히 예측 불가능해야 함
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        TeamInvite invite = teamInviteRepository.save(TeamInvite.builder()
                .team(team).token(token)
                .expiresAt(LocalDateTime.now().plusDays(INVITE_VALID_DAYS))
                .build());

        return new TeamInviteResponse(invite.getToken(), invite.getExpiresAt());
    }

    @Transactional
    public TeamResponse joinByToken(Long userId, String token) {
        TeamInvite invite = teamInviteRepository.findByToken(token)
                .orElseThrow(() -> ApiException.notFound("유효하지 않은 초대 링크입니다"));
        if (invite.isExpired()) {
            throw ApiException.badRequest("만료된 초대 링크입니다");
        }

        Team team = invite.getTeam();
        // 이미 멤버면 에러 대신 그냥 통과 (초대 링크는 여러 번 눌러도 안전해야 함 - 멱등성)
        if (teamMemberRepository.existsByTeamIdAndUserId(team.getId(), userId)) {
            return TeamResponse.from(team);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다"));
        teamMemberRepository.save(TeamMember.builder()
                .team(team).user(user).teamRole(TeamMember.TeamRole.MEMBER).build());

        return TeamResponse.from(team);
    }

    @Transactional(readOnly = true)
    public List<TeamMemberResponse> members(Long userId, Long teamId) {
        requireMember(teamId, userId);
        return teamMemberRepository.findByTeamId(teamId).stream()
                .map(TeamMemberResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TeamUsageSummaryResponse teamUsage(Long userId, Long teamId, LocalDateTime from, LocalDateTime to) {
        requireMember(teamId, userId);
        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusMonths(1);
        LocalDateTime effectiveTo = to != null ? to : LocalDateTime.now();
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw ApiException.badRequest("from은 to보다 이전이어야 합니다");
        }
        return requestLogRepository.aggregateByTeam(teamId, effectiveFrom, effectiveTo);
    }

    // 본인이 이 팀에서 쓸 닉네임 설정(멤버별 사용량 표에 이메일 대신 표시). 빈 값이면 해제.
    @Transactional
    public void setMyNickname(Long userId, Long teamId, String nickname) {
        TeamMember member = requireMember(teamId, userId);
        String trimmed = (nickname == null || nickname.isBlank()) ? null : nickname.trim();
        member.changeNickname(trimmed); // dirty checking으로 커밋 시 UPDATE
    }

    // /teams/{id}/usage/members - 팀 사용량을 멤버별로 쪼갠다. 로그 집계(사용자별)를 팀 멤버 목록과
    // 합쳐 displayName(닉네임 없으면 이메일)을 채운다. 사용 기록이 없는 멤버도 0으로 포함한다.
    @Transactional(readOnly = true)
    public List<TeamMemberUsageResponse> teamMemberUsage(Long userId, Long teamId,
                                                         LocalDateTime from, LocalDateTime to) {
        requireMember(teamId, userId);
        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusMonths(1);
        LocalDateTime effectiveTo = to != null ? to : LocalDateTime.now();
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw ApiException.badRequest("from은 to보다 이전이어야 합니다");
        }
        Map<Long, TeamMemberUsageRow> byUser = requestLogRepository
                .aggregateTeamByMember(teamId, effectiveFrom, effectiveTo).stream()
                .collect(Collectors.toMap(TeamMemberUsageRow::userId, r -> r));

        return teamMemberRepository.findByTeamId(teamId).stream().map(m -> {
            Long uid = m.getUser().getId();
            String displayName = (m.getNickname() != null && !m.getNickname().isBlank())
                    ? m.getNickname() : m.getUser().getEmail();
            TeamMemberUsageRow row = byUser.get(uid);
            return new TeamMemberUsageResponse(uid, displayName,
                    row != null ? row.totalCost() : BigDecimal.ZERO,
                    row != null ? row.totalTokens() : 0L,
                    row != null ? row.requestCount() : 0L);
        }).toList();
    }

    // 본인 탈퇴: 멤버가 팀에서 나간다. 나가면 그 사람이 발급한 팀 tlm_ 키는 자동 폐기(더는 그 사람
    // 명의로 팀 자원을 못 쓰게). OWNER는 그냥 나갈 수 없다(팀을 해지하거나 넘겨야) - 팀이 주인 없이
    // 남는 걸 막기 위함. 인증 캐시(API_KEY_AUTH)를 비워 폐기가 즉시 반영되게 한다.
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.API_KEY_AUTH, allEntries = true)
    public void leaveTeam(Long userId, Long teamId) {
        TeamMember me = requireMember(teamId, userId);
        if (me.getTeamRole() == TeamMember.TeamRole.OWNER) {
            throw ApiException.badRequest("OWNER는 팀을 해지하거나 다른 멤버에게 넘긴 뒤 나갈 수 있습니다");
        }
        apiKeyRepository.findByTeamIdAndUserId(teamId, userId).forEach(ApiKey::revoke);
        teamMemberRepository.delete(me);
    }

    // 강제퇴장: OWNER가 다른 멤버를 내보낸다. 내보낸 멤버가 발급한 팀 키도 자동 폐기.
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.API_KEY_AUTH, allEntries = true)
    public void removeMember(Long ownerUserId, Long teamId, Long targetUserId) {
        requireOwner(teamId, ownerUserId);
        if (targetUserId.equals(ownerUserId)) {
            throw ApiException.badRequest("본인은 강제퇴장 대상이 아닙니다 (탈퇴 또는 팀 해지를 사용하세요)");
        }
        TeamMember target = teamMemberRepository.findByTeamIdAndUserId(teamId, targetUserId)
                .orElseThrow(() -> ApiException.notFound("해당 팀원을 찾을 수 없습니다"));
        apiKeyRepository.findByTeamIdAndUserId(teamId, targetUserId).forEach(ApiKey::revoke);
        teamMemberRepository.delete(target);
    }

    // 팀 해지: OWNER가 팀을 없앤다. 팀의 모든 tlm_ 키 폐기 + 원문 BYOK 키(암호문) 삭제 + 초대/쿼터/멤버 정리.
    // team 행 자체는 request_log/api_key가 FK로 참조하므로 지우지 않는다(이력 보존). 멤버가 0이 되면
    // 어느 사용자의 /teams/mine에도 안 나오고, 남은 키는 전부 폐기되어 사실상 사라진 것과 같다.
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.API_KEY_AUTH, allEntries = true)
    public void disbandTeam(Long userId, Long teamId) {
        requireOwner(teamId, userId);
        apiKeyRepository.findByTeamId(teamId).forEach(ApiKey::revoke);       // 톨름 팀 키 전부 폐기
        teamProviderCredentialRepository.deleteByTeamId(teamId);            // 원문 프로바이더 키 삭제
        teamInviteRepository.deleteByTeamId(teamId);
        teamUsageQuotaRepository.findByTeamId(teamId).ifPresent(teamUsageQuotaRepository::delete);
        teamMemberRepository.deleteByTeamId(teamId);
    }

    private TeamMember requireMember(Long teamId, Long userId) {
        return teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> ApiException.forbidden("팀 멤버만 접근할 수 있습니다"));
    }

    private TeamMember requireOwner(Long teamId, Long userId) {
        TeamMember member = requireMember(teamId, userId);
        if (member.getTeamRole() != TeamMember.TeamRole.OWNER) {
            throw ApiException.forbidden("팀 OWNER만 가능합니다");
        }
        return member;
    }
}
