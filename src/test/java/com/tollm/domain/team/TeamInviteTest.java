package com.tollm.domain.team;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TeamInviteTest {

    private Team team() {
        return Team.builder().name("팀").build();
    }

    @Test
    void 만료시각_이전이면_만료가_아니다() {
        TeamInvite invite = TeamInvite.builder().team(team()).token("tok")
                .expiresAt(LocalDateTime.now().plusDays(7)).build();

        assertThat(invite.isExpired()).isFalse();
    }

    @Test
    void 만료시각이_지났으면_만료다() {
        TeamInvite invite = TeamInvite.builder().team(team()).token("tok")
                .expiresAt(LocalDateTime.now().minusSeconds(1)).build();

        assertThat(invite.isExpired()).isTrue();
    }
}
