package com.tollm.domain.team;

import com.tollm.domain.apikey.ApiKeyService;
import com.tollm.domain.apikey.dto.ApiKeyIssueResponse;
import com.tollm.domain.apikey.dto.ApiKeySummary;
import com.tollm.domain.team.dto.*;
import com.tollm.domain.usage.dto.TeamUsageSummaryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

// 팀 기능은 인증된 사용자가 "개인으로 쓰다가 팀으로도 쓰고 싶을 때" 얹는 add-on이다.
// 그래서 이 컨트롤러의 모든 엔드포인트도 JwtAuthFilter가 보호하는 /teams/** 경로 그대로 쓴다
// (기존 필터 설정 무변경 - PROTECTED_PREFIXES에 /teams가 이미 1주차부터 포함돼 있었음).
@RestController
@RequestMapping("/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;
    private final ApiKeyService apiKeyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TeamResponse createTeam(@RequestAttribute("userId") Long userId,
                                    @Valid @RequestBody CreateTeamRequest request) {
        return teamService.createTeam(userId, request);
    }

    // 대시보드의 "팀 선택" 드롭다운을 채우는 용도 - 본인이 속한 팀 + 역할 목록
    @GetMapping("/mine")
    public List<MyTeamResponse> myTeams(@RequestAttribute("userId") Long userId) {
        return teamService.myTeams(userId);
    }

    // 초대 링크(토큰) 발급 - OWNER만 (TeamService에서 검증)
    @PostMapping("/{teamId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamInviteResponse createInvite(@RequestAttribute("userId") Long userId, @PathVariable Long teamId) {
        return teamService.createInvite(userId, teamId);
    }

    // 초대 링크로 합류 - 이미 멤버면 멱등하게 통과 (TeamService 참고)
    @PostMapping("/join/{token}")
    public TeamResponse join(@RequestAttribute("userId") Long userId, @PathVariable String token) {
        return teamService.joinByToken(userId, token);
    }

    @GetMapping("/{teamId}/members")
    public List<TeamMemberResponse> members(@RequestAttribute("userId") Long userId, @PathVariable Long teamId) {
        return teamService.members(userId, teamId);
    }

    @GetMapping("/{teamId}/usage")
    public TeamUsageSummaryResponse teamUsage(
            @RequestAttribute("userId") Long userId, @PathVariable Long teamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return teamService.teamUsage(userId, teamId, from, to);
    }

    // ---- 팀 API 키 ----
    // 발급/조회/폐기 로직 자체는 ApiKeyService(팀 멤버십 검증 포함)에 위임 -
    // 이 컨트롤러는 URL 라우팅만 담당한다 (개인 키 ApiKeyController와 같은 얇은 컨트롤러 원칙)

    @PostMapping("/{teamId}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyIssueResponse issueTeamKey(@RequestAttribute("userId") Long userId, @PathVariable Long teamId) {
        return apiKeyService.issueForTeam(userId, teamId);
    }

    @GetMapping("/{teamId}/keys")
    public List<ApiKeySummary> teamKeys(@RequestAttribute("userId") Long userId, @PathVariable Long teamId) {
        return apiKeyService.teamKeys(userId, teamId);
    }

    @DeleteMapping("/{teamId}/keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeTeamKey(@RequestAttribute("userId") Long userId,
                              @PathVariable Long teamId, @PathVariable Long keyId) {
        apiKeyService.revokeTeamKey(userId, teamId, keyId);
    }
}
