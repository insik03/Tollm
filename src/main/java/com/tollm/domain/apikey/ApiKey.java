package com.tollm.domain.apikey;

import com.tollm.domain.team.Team;
import com.tollm.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiKey {

    public enum Status { ACTIVE, REVOKED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 키를 실제로 "발급 버튼을 눌러 만든" 사람. 팀 키여도 계속 채워진다(감사 추적용) - null 허용하지 않음
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 팀 키일 때만 채워진다. null이면 기존과 동일한 개인 키 - 기존 로직/데이터는 전혀 영향받지 않는다.
    // (설계 근거: 팀 기능을 "재설계"가 아니라 "추가"로 넣기 위한 핵심 결정 - README 참고)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    // 원문 키는 저장하지 않음. SHA-256 해시만 저장 (발급 시 1회만 원문 노출)
    @Column(nullable = false, unique = true)
    private String keyHash;

    // 식별용 접두어 예: tlm_a1b2 (목록에서 어떤 키인지 구분용)
    @Column(nullable = false)
    private String prefix;

    @Enumerated(EnumType.STRING)
    private Status status;

    private LocalDateTime createdAt;
    private LocalDateTime expiredAt;

    @Builder
    public ApiKey(User user, Team team, String keyHash, String prefix, LocalDateTime expiredAt) {
        this.user = user;
        this.team = team;
        this.keyHash = keyHash;
        this.prefix = prefix;
        this.status = Status.ACTIVE;
        this.createdAt = LocalDateTime.now();
        this.expiredAt = expiredAt;
    }

    public boolean isTeamKey() {
        return team != null;
    }

    public void revoke() { this.status = Status.REVOKED; }
}
