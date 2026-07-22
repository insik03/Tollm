package com.tollm.domain.team;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Team {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // 월 예산은 이 필드가 아니라 TeamUsageQuota(개인 UsageQuota와 동일 패턴)로 관리한다 -
    // 팀 생성 시 TeamService가 함께 만든다 (AuthService가 가입 시 UsageQuota를 만드는 것과 동일한 이유:
    // 원자성이 필요한 두 저장을 한 트랜잭션으로 묶기 위함)
    @Builder
    public Team(String name) {
        this.name = name;
    }
}
