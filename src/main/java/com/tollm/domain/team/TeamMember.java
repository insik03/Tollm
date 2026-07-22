package com.tollm.domain.team;

import com.tollm.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

// User <-> Team 다대다를 풀어주는 중간 엔티티 (팀 내 역할 포함)
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamMember {

    public enum TeamRole { OWNER, MEMBER }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    private TeamRole teamRole;

    private LocalDateTime createdAt;

    @Builder
    public TeamMember(Team team, User user, TeamRole teamRole) {
        this.team = team;
        this.user = user;
        this.teamRole = teamRole;
        this.createdAt = LocalDateTime.now();
    }
}
