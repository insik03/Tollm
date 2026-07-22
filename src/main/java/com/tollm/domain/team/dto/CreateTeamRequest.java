package com.tollm.domain.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateTeamRequest(
        @NotBlank String name,
        @Positive BigDecimal monthlyBudget
) {
}
