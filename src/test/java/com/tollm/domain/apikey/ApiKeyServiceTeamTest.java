package com.tollm.domain.apikey;

import com.tollm.domain.apikey.dto.ApiKeyIssueResponse;
import com.tollm.domain.team.Team;
import com.tollm.domain.team.TeamMember;
import com.tollm.domain.team.TeamMemberRepository;
import com.tollm.domain.team.TeamRepository;
import com.tollm.domain.usage.RequestLogRepository;
import com.tollm.domain.usage.dto.UsageSummaryResponse;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// 팀 키(add-on) 전용 단위 테스트. 개인 키 발급/조회/폐기(issue/myKeys/revoke)는 이번 변경으로
// 로직이 한 줄도 안 바뀌었으므로(issueInternal로 추출만 됨) 별도로 다시 테스트하지 않는다 -
// 이 파일은 새로 추가된 팀 멤버십/역할 기반 인가 로직만 검증한다.
@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTeamTest {

    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private UserRepository userRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private TeamMemberRepository teamMemberRepository;
    @Mock private RequestLogRepository requestLogRepository;

    private ApiKeyService apiKeyService;

    private static final Long CALLER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long TEAM_ID = 10L;
    private static final Long OTHER_TEAM_ID = 99L;
    private static final Long KEY_ID = 100L;

    @BeforeEach
    void setUp() {
        apiKeyService = new ApiKeyService(apiKeyRepository, userRepository, teamRepository, teamMemberRepository, requestLogRepository);
    }

    private User userWithId(Long id) {
        User user = mock(User.class);
        given(user.getId()).willReturn(id);
        return user;
    }

    private Team teamWithId(Long id) {
        Team team = mock(Team.class);
        given(team.getId()).willReturn(id);
        return team;
    }

    // ApiKeyService.requireMember()의 유일한 소비처는 getTeamRole()뿐이라(issue/revoke 권한 판단),
    // team/user 필드는 getId() 등을 stub해봐야 아무도 안 읽어서 Mockito strict stubs가
    // "쓰이지 않은 stub"으로 실패시킨다 - stub 없는 순수 mock으로 채운다
    private TeamMember memberOf(TeamMember.TeamRole role) {
        return TeamMember.builder().team(mock(Team.class)).user(mock(User.class)).teamRole(role).build();
    }

    @Test
    void 팀_멤버가_아니면_팀_키_발급이_403() {
        given(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> apiKeyService.issueForTeam(CALLER_ID, TEAM_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(403));
    }

    @Test
    void 일반_MEMBER도_팀_키를_발급할_수_있고_team이_채워진다() {
        // [주의] given(A).willReturn(nestedMockStub())처럼 아직 안 끝난 given() 체인의 인자 자리에서
        // 또 다른 mock을 스텁하면 Mockito가 "어느 스텁을 완성하는 중인지" 헷갈려 UnfinishedStubbingException이
        // 난다. 그래서 mock 생성/스텁이 필요한 값은 항상 given() 호출 전에 지역변수로 먼저 만들어 둔다.
        // issueInternal()은 user/team의 getId()를 읽지 않고(그냥 연관관계로 저장만) 반환 DTO도
        // saved.getId()만 쓰므로, 여기서는 getId() stub이 필요 없는 순수 mock을 쓴다
        TeamMember member = memberOf(TeamMember.TeamRole.MEMBER);
        User user = mock(User.class);
        Team team = mock(Team.class);
        given(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID)).willReturn(Optional.of(member));
        given(userRepository.findById(CALLER_ID)).willReturn(Optional.of(user));
        given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
        given(apiKeyRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ApiKeyIssueResponse response = apiKeyService.issueForTeam(CALLER_ID, TEAM_ID);

        assertThat(response.key()).startsWith("tlm_");
        assertThat(response.prefix()).isEqualTo(response.key().substring(0, 8));
    }

    @Test
    void 키를_발급한_본인이면_MEMBER여도_폐기할_수_있다() {
        ApiKey key = ApiKey.builder().user(userWithId(CALLER_ID)).team(teamWithId(TEAM_ID))
                .keyHash("h").prefix("tlm_abcd").build();
        TeamMember member = memberOf(TeamMember.TeamRole.MEMBER);
        given(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID)).willReturn(Optional.of(member));
        given(apiKeyRepository.findById(KEY_ID)).willReturn(Optional.of(key));

        apiKeyService.revokeTeamKey(CALLER_ID, TEAM_ID, KEY_ID);

        assertThat(key.getStatus()).isEqualTo(ApiKey.Status.REVOKED);
    }

    @Test
    void OWNER는_본인이_발급하지_않은_팀_키도_폐기할_수_있다() {
        ApiKey key = ApiKey.builder().user(userWithId(OTHER_USER_ID)).team(teamWithId(TEAM_ID))
                .keyHash("h").prefix("tlm_abcd").build();
        TeamMember member = memberOf(TeamMember.TeamRole.OWNER);
        given(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID)).willReturn(Optional.of(member));
        given(apiKeyRepository.findById(KEY_ID)).willReturn(Optional.of(key));

        apiKeyService.revokeTeamKey(CALLER_ID, TEAM_ID, KEY_ID);

        assertThat(key.getStatus()).isEqualTo(ApiKey.Status.REVOKED);
    }

    @Test
    void 일반_MEMBER는_남이_발급한_팀_키를_폐기할_수_없다() {
        ApiKey key = ApiKey.builder().user(userWithId(OTHER_USER_ID)).team(teamWithId(TEAM_ID))
                .keyHash("h").prefix("tlm_abcd").build();
        TeamMember member = memberOf(TeamMember.TeamRole.MEMBER);
        given(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID)).willReturn(Optional.of(member));
        given(apiKeyRepository.findById(KEY_ID)).willReturn(Optional.of(key));

        assertThatThrownBy(() -> apiKeyService.revokeTeamKey(CALLER_ID, TEAM_ID, KEY_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(403));
        assertThat(key.getStatus()).isEqualTo(ApiKey.Status.ACTIVE);
    }

    @Test
    void 다른_팀_소속_키는_404() {
        // 팀 불일치 검사(key.getTeam().getId())에서 먼저 막혀 key.getUser()는 안 읽힌다 - stub 없는 mock
        ApiKey key = ApiKey.builder().user(mock(User.class)).team(teamWithId(OTHER_TEAM_ID))
                .keyHash("h").prefix("tlm_abcd").build();
        TeamMember member = memberOf(TeamMember.TeamRole.OWNER);
        given(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID)).willReturn(Optional.of(member));
        given(apiKeyRepository.findById(KEY_ID)).willReturn(Optional.of(key));

        assertThatThrownBy(() -> apiKeyService.revokeTeamKey(CALLER_ID, TEAM_ID, KEY_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(404));
    }

    // ---- 키 단위 사용량 (add-on) ----

    @Test
    void 본인_개인_키_사용량은_조회할_수_있다() {
        ApiKey key = ApiKey.builder().user(userWithId(CALLER_ID)).keyHash("h").prefix("tlm_abcd").build();
        UsageSummaryResponse expected = new UsageSummaryResponse(BigDecimal.ONE, 100L, 5L, 1L);
        given(apiKeyRepository.findById(KEY_ID)).willReturn(Optional.of(key));
        given(requestLogRepository.aggregateByApiKey(eq(KEY_ID), any(), any())).willReturn(expected);

        UsageSummaryResponse result = apiKeyService.keyUsage(CALLER_ID, KEY_ID, null, null);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void 남의_개인_키_사용량은_조회할_수_없다() {
        ApiKey key = ApiKey.builder().user(userWithId(OTHER_USER_ID)).keyHash("h").prefix("tlm_abcd").build();
        given(apiKeyRepository.findById(KEY_ID)).willReturn(Optional.of(key));

        assertThatThrownBy(() -> apiKeyService.keyUsage(CALLER_ID, KEY_ID, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(403));
    }

    // [핵심 회귀] 팀 키는 keyUsage()가 아니라 teamKeyUsage()로만 조회해야 한다 -
    // 개인/팀 사용량 구분을 API 레벨에서도 강제한다
    @Test
    void 팀_키는_개인_키_사용량_엔드포인트로_조회할_수_없다() {
        // isTeamKey()가 ||의 앞 항이라 true면 뒤(key.getUser())는 평가되지 않는다(short-circuit) -
        // 즉 여기선 user/team의 getId() 값 자체가 안 쓰이므로 stub 없는 mock을 쓴다
        ApiKey teamKey = ApiKey.builder().user(mock(User.class)).team(mock(Team.class))
                .keyHash("h").prefix("tlm_abcd").build();
        given(apiKeyRepository.findById(KEY_ID)).willReturn(Optional.of(teamKey));

        assertThatThrownBy(() -> apiKeyService.keyUsage(CALLER_ID, KEY_ID, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus().value()).isEqualTo(403));
    }

    @Test
    void 팀_멤버는_팀_키_사용량을_조회할_수_있다() {
        // teamKeyUsage()는 팀 일치 여부(key.getTeam().getId())만 확인하고 발급자가 누군지는
        // 안 보므로(누구나 팀 사용량을 볼 수 있음) key.getUser()는 stub 없는 mock으로 충분하다
        ApiKey key = ApiKey.builder().user(mock(User.class)).team(teamWithId(TEAM_ID))
                .keyHash("h").prefix("tlm_abcd").build();
        TeamMember member = memberOf(TeamMember.TeamRole.MEMBER);
        UsageSummaryResponse expected = new UsageSummaryResponse(BigDecimal.TEN, 200L, 3L, 0L);
        given(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID)).willReturn(Optional.of(member));
        given(apiKeyRepository.findById(KEY_ID)).willReturn(Optional.of(key));
        given(requestLogRepository.aggregateByApiKey(eq(KEY_ID), any(), any())).willReturn(expected);

        UsageSummaryResponse result = apiKeyService.teamKeyUsage(CALLER_ID, TEAM_ID, KEY_ID, null, null);

        assertThat(result).isEqualTo(expected);
    }
}
