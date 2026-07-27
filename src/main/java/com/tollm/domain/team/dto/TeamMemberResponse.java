package com.tollm.domain.team.dto;

import com.tollm.domain.team.TeamMember;

import java.time.LocalDateTime;

public record TeamMemberResponse(Long userId, String email, String nickname, String role, LocalDateTime joinedAt) {
    public static TeamMemberResponse from(TeamMember member) {
        return new TeamMemberResponse(
                member.getUser().getId(), member.getUser().getEmail(), member.getNickname(),
                member.getTeamRole().name(), member.getCreatedAt());
    }
}
