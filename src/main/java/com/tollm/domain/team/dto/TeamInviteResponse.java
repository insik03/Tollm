package com.tollm.domain.team.dto;

import java.time.LocalDateTime;

public record TeamInviteResponse(String token, LocalDateTime expiresAt) {
}
