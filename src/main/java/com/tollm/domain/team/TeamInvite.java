package com.tollm.domain.team;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

// 팀 초대 링크. 단발성 토큰(1회 사용 후 소멸)이 아니라, 디스코드/슬랙 초대 링크처럼
// 만료 전까지는 누구든 같은 링크로 여러 번 합류를 시도할 수 있는 방식을 택했다
// (이미 멤버인 사람이 다시 시도하면 TeamService에서 멱등하게 처리 - 에러 대신 그냥 통과).
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamInvite {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    // API 키와 같은 이유로 원문을 그대로 저장(해시 아님) - 이 토큰은 재발급 가능한 초대 링크일 뿐,
    // 유출돼도 그 팀에 "합류"만 가능하지 인증 수단(로그인/과금)이 아니라서 API 키만큼 민감하지 않다
    @Column(nullable = false, unique = true)
    private String token;

    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    @Builder
    public TeamInvite(Team team, String token, LocalDateTime expiresAt) {
        this.team = team;
        this.token = token;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}
