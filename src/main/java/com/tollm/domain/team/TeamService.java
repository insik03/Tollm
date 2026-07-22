package com.tollm.domain.team;

import com.tollm.domain.team.dto.*;
import com.tollm.domain.usage.RequestLogRepository;
import com.tollm.domain.usage.dto.TeamUsageSummaryResponse;
import com.tollm.domain.user.User;
import com.tollm.domain.user.UserRepository;
import com.tollm.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

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
