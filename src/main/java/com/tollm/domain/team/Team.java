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

    // 팀 전체 월 예산 (원 단위). 팀원 개인 쿼터와 별도로 체크
    private Long monthlyBudget;

    @Builder
    public Team(String name, Long monthlyBudget) {
        this.name = name;
        this.monthlyBudget = monthlyBudget;
    }
}
