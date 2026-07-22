package com.tollm.domain.team.dto;

import com.tollm.domain.team.Team;

public record TeamResponse(Long id, String name) {
    public static TeamResponse from(Team team) {
        return new TeamResponse(team.getId(), team.getName());
    }
}
