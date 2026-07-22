package com.tollm.domain.team;

import com.tollm.domain.team.dto.CreateTeamRequest;
import com.tollm.domain.team.dto.TeamInviteResponse;
import com.tollm.domain.team.dto.TeamResponse;
import com.tollm.domain.usage.RequestLogRepository;
import com.tollm.domain.user.Role;
import com.tollm.domain.user.User;
import com.tollm.domain.user.UserRepository;
import com.tollm.global.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// mock 기반 단위 테스트 - 이 테스트의 목적은 "권한 검증(OWNER만 초대 발급 등)과 초대 만료/멱등
// 합류 같은 새로 추가된 분기 로직이 올바른가"이지 인프라 검증이 아니다 (ProxyServiceTest와 같은 원칙)
@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    @Mock private TeamRepository teamRepository;
    @Mock private TeamMemberRepository teamMemberRepository;
    @Mock private TeamInviteRepository teamInviteRepository;
    @Mock private TeamUsageQuotaRepository teamUsageQuotaRepository;
    @Mock private RequestLogRepository requestLogRepository;
    @Mock private UserRepository userRepository;

    private TeamService teamService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;

    @BeforeEach
    void setUp() {
        teamService = new TeamService(teamRepository, teamMemberRepository, teamInviteRepository,
                teamUsageQuotaRepository, requestLogRepository, userRepository);
    }

    private User user() {
        return User.builder().email("owner@test.com").password("h").role(Role.MEMBER).build();
    }

    // getId()/getName()까지 stub이 필요한 경우(응답 DTO가 실제로 그 값을 읽는 테스트)에만 쓴다.
    // 그렇지 않은 테스트에서 이걸 쓰면 Mockito strict stubs가 "쓰이지 않은 stub"으로 실패시킨다 -
    // 아래 plainTeam()이 그런 경우(권한 검증에서 먼저 막혀 team 필드를 읽을 일이 없는 테스트)를 위한 것
    private Team teamMock(Long id, String name) {
        Team team = mock(Team.class);
        given(team.getId()).willReturn(id);
        given(team.getName()).willReturn(name);
        return team;
    }

    private Team plainTeam(String name) {
        return Team.builder().name(name).build();
    }

    @Test
    void 팀_생성시_OWNER로_등록되고_팀_쿼터도_함께_생성된다() {
        Team team = teamMock(TEAM_ID, "백엔드 스터디");
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));
        given(teamRepository.save(any())).willReturn(team);

        TeamResponse response = teamService.createTeam(USER_ID, new CreateTeamRequest("백엔드 스터디", BigDecimal.TEN));

        assertThat(response.id()).isEqualTo(TEAM_ID);
        assertThat(response.name()).isEqualTo("백엔드 스터디");
        verify(teamMemberRepository).save(argThatMember(m -> m.getTeamRole() == TeamMember.TeamRole.OWNER));
        verify(teamUsageQuotaRepository).save(argThatQuota(q -> q.getMonthlyCostLimit().compareTo(BigDecimal.TEN) == 0));
    }

    @Test
    void 존재하지_않는_사용자면_팀_생성이_404() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.createTeam(USER_ID, new CreateTeamRequest("팀", BigDecimal.TEN)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void OWNER가_아니면_초대_링크를_만들_수_없다() {
        // requireOwner()가 역할 검사에서 먼저 막히므로 team 필드는 읽히지 않는다 - plainTeam() 사용
        TeamMember memberRole = TeamMember.builder().team(plainTeam("팀")).user(user())
                .teamRole(TeamMember.TeamRole.MEMBER).build();
        given(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, USER_ID)).willReturn(Optional.of(memberRole));

        assertThatThrownBy(() -> teamService.createInvite(USER_ID, TEAM_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(403));
    }

    @Test
    void OWNER는_초대_링크를_만들_수_있고_7일_후_만료다() {
        // TeamInviteResponse는 token/expiresAt만 담으므로 team.getId()/getName()은 안 읽힌다 - plainTeam()
        Team team = plainTeam("팀");
        TeamMember ownerRole = TeamMember.builder().team(team).user(user())
                .teamRole(TeamMember.TeamRole.OWNER).build();
        given(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, USER_ID)).willReturn(Optional.of(ownerRole));
        given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
        given(teamInviteRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        LocalDateTime before = LocalDateTime.now();
        TeamInviteResponse response = teamService.createInvite(USER_ID, TEAM_ID);

        assertThat(response.token()).isNotBlank();
        assertThat(response.expiresAt()).isAfter(before.plusDays(6));
        assertThat(response.expiresAt()).isBefore(before.plusDays(8));
    }

    @Test
    void 만료된_초대_토큰으로는_합류할_수_없다() {
        // isExpired() 검사에서 먼저 막히므로 team 필드는 읽히지 않는다 - plainTeam() 사용
        TeamInvite expired = TeamInvite.builder().team(plainTeam("팀")).token("tok")
                .expiresAt(LocalDateTime.now().minusMinutes(1)).build();
        given(teamInviteRepository.findByToken("tok")).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> teamService.joinByToken(USER_ID, "tok"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    void 유효한_토큰으로_합류하면_MEMBER로_등록된다() {
        Team team = teamMock(TEAM_ID, "팀");
        TeamInvite invite = TeamInvite.builder().team(team).token("tok")
                .expiresAt(LocalDateTime.now().plusDays(1)).build();
        given(teamInviteRepository.findByToken("tok")).willReturn(Optional.of(invite));
        given(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).willReturn(false);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));

        TeamResponse response = teamService.joinByToken(USER_ID, "tok");

        assertThat(response.id()).isEqualTo(TEAM_ID);
        verify(teamMemberRepository).save(argThatMember(m -> m.getTeamRole() == TeamMember.TeamRole.MEMBER));
    }

    // 초대 링크는 여러 번 눌러도 안전해야 한다(디스코드/슬랙과 같은 UX) - 에러 대신 조용히 통과
    @Test
    void 이미_멤버면_다시_합류해도_에러없이_멱등하게_통과한다() {
        Team team = teamMock(TEAM_ID, "팀");
        TeamInvite invite = TeamInvite.builder().team(team).token("tok")
                .expiresAt(LocalDateTime.now().plusDays(1)).build();
        given(teamInviteRepository.findByToken("tok")).willReturn(Optional.of(invite));
        given(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).willReturn(true);

        TeamResponse response = teamService.joinByToken(USER_ID, "tok");

        assertThat(response.id()).isEqualTo(TEAM_ID);
        verify(teamMemberRepository, never()).save(any());
    }

    @Test
    void 팀_멤버가_아니면_멤버_목록_조회가_403() {
        given(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.members(USER_ID, TEAM_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(403));
    }

    // ArgumentMatchers.argThat은 제네릭 추론이 불안정해서 타입 명시용 작은 래퍼를 둔다
    private TeamMember argThatMember(java.util.function.Predicate<TeamMember> p) {
        return org.mockito.ArgumentMatchers.argThat(p::test);
    }

    private TeamUsageQuota argThatQuota(java.util.function.Predicate<TeamUsageQuota> p) {
        return org.mockito.ArgumentMatchers.argThat(p::test);
    }
}
