package com.tollm.domain.team;

import com.tollm.domain.apikey.ApiKeyService;
import com.tollm.domain.apikey.dto.ApiKeyIssueResponse;
import com.tollm.domain.apikey.dto.ApiKeySummary;
import com.tollm.domain.providerkey.ProviderKeyService;
import com.tollm.domain.providerkey.dto.ProviderKeyRegisterRequest;
import com.tollm.domain.providerkey.dto.ProviderKeySummary;
import com.tollm.domain.team.dto.*;
import com.tollm.domain.usage.dto.TeamUsageSummaryResponse;
import com.tollm.domain.usage.dto.UsageSummaryResponse;
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
    private final ProviderKeyService providerKeyService;

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

    // 팀 사용량을 멤버별로 - 누가 얼마나 썼는지(닉네임/이메일 기준)
    @GetMapping("/{teamId}/usage/members")
    public List<TeamMemberUsageResponse> teamMemberUsage(
            @RequestAttribute("userId") Long userId, @PathVariable Long teamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return teamService.teamMemberUsage(userId, teamId, from, to);
    }

    // 팀 안에서 쓸 본인 닉네임 설정
    @PatchMapping("/{teamId}/members/me/nickname")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setMyNickname(@RequestAttribute("userId") Long userId, @PathVariable Long teamId,
                              @Valid @RequestBody SetNicknameRequest request) {
        teamService.setMyNickname(userId, teamId, request.nickname());
    }

    // 본인 탈퇴 (탈퇴하면 본인이 발급한 팀 키 자동 폐기)
    @DeleteMapping("/{teamId}/members/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leaveTeam(@RequestAttribute("userId") Long userId, @PathVariable Long teamId) {
        teamService.leaveTeam(userId, teamId);
    }

    // 강제퇴장 (OWNER만, 대상 멤버가 발급한 팀 키 자동 폐기)
    @DeleteMapping("/{teamId}/members/{targetUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@RequestAttribute("userId") Long userId, @PathVariable Long teamId,
                             @PathVariable Long targetUserId) {
        teamService.removeMember(userId, teamId, targetUserId);
    }

    // 팀 해지 (OWNER만, 팀의 모든 tlm_ 키 폐기 + 원문 BYOK 키 삭제)
    @DeleteMapping("/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disbandTeam(@RequestAttribute("userId") Long userId, @PathVariable Long teamId) {
        teamService.disbandTeam(userId, teamId);
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

    // 팀 활성 키 [사용/최대] 안내
    @GetMapping("/{teamId}/keys/limit")
    public com.tollm.domain.apikey.dto.ApiKeyLimitResponse teamKeyLimit(
            @RequestAttribute("userId") Long userId, @PathVariable Long teamId) {
        return apiKeyService.teamKeyLimit(userId, teamId);
    }

    @DeleteMapping("/{teamId}/keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeTeamKey(@RequestAttribute("userId") Long userId,
                              @PathVariable Long teamId, @PathVariable Long keyId) {
        apiKeyService.revokeTeamKey(userId, teamId, keyId);
    }

    // 팀 키 "하나"의 사용량 - /teams/{id}/usage(팀 전체 합산)와 별개로, 여러 팀 키 중
    // 어느 키가 얼마나 썼는지 구분해서 보고 싶다는 요구로 추가
    @GetMapping("/{teamId}/keys/{keyId}/usage")
    public UsageSummaryResponse teamKeyUsage(
            @RequestAttribute("userId") Long userId, @PathVariable Long teamId, @PathVariable Long keyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return apiKeyService.teamKeyUsage(userId, teamId, keyId, from, to);
    }

    // ---- 팀 LLM 프로바이더 키 (BYOK) ----
    // 팀 요청은 이 팀 키로만 나간다(서버 공용키 폴백 없음). 등록/삭제는 OWNER만, 조회(마스킹)는 팀원 누구나.
    @PostMapping("/{teamId}/provider-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderKeySummary registerTeamProviderKey(@RequestAttribute("userId") Long userId,
                                                      @PathVariable Long teamId,
                                                      @Valid @RequestBody ProviderKeyRegisterRequest request) {
        return providerKeyService.registerTeamKey(userId, teamId, request.provider(), request.apiKey());
    }

    @GetMapping("/{teamId}/provider-keys")
    public List<ProviderKeySummary> teamProviderKeys(@RequestAttribute("userId") Long userId, @PathVariable Long teamId) {
        return providerKeyService.teamKeys(userId, teamId);
    }

    @DeleteMapping("/{teamId}/provider-keys/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTeamProviderKey(@RequestAttribute("userId") Long userId,
                                      @PathVariable Long teamId, @PathVariable String provider) {
        providerKeyService.deleteTeamKey(userId, teamId, provider);
    }
}
