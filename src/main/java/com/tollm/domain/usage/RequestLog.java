package com.tollm.domain.usage;

import com.tollm.domain.apikey.ApiKey;
import com.tollm.domain.team.Team;
import com.tollm.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

// 요청마다 1행씩 쌓이는 대용량 테이블 -> 인덱스 설계가 성능 학습 포인트
@Entity
@Table(indexes = {
    @Index(name = "idx_log_user_created", columnList = "user_id, createdAt"),
    @Index(name = "idx_log_team_created", columnList = "team_id, createdAt"),
    @Index(name = "idx_log_apikey_created", columnList = "api_key_id, createdAt")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 팀 키로 온 요청이어도 이 필드는 "키를 발급한 사람"으로 계속 채워진다(FK 무결성 유지,
    // 개인 요청과 완전히 같은 컬럼 구조). 팀 단위 집계는 team이 채워진 행만 따로 걸러서 한다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 팀 키로 온 요청일 때만 채워짐 (ApiKey.team과 동일한 설계 원칙)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    // 이 요청이 어떤 API 키로 왔는지. 프록시 요청은 항상 인증된 키가 있어야 도달하므로 실질적으로는
    // 거의 항상 채워지지만, "요청 1건 = 반드시 특정 키 1개"라는 전제를 스키마 제약(NOT NULL)으로
    // 강제하지는 않는다 - team/apiKey 둘 다 같은 add-on 원칙(선택적 참조 추가, 기존 컬럼 무변경)을 따른다.
    // 이 필드 덕분에 "사용자당 합산"이 아니라 "키 하나당 얼마나 썼는지"를 구분할 수 있다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_key_id")
    private ApiKey apiKey;

    private String model;
    private String providerName;
    private Integer inputTokens;
    private Integer outputTokens;

    // 기본값(DECIMAL(19,2))으로 두면 LLM 요청 1건당 비용(보통 $0.01 미만, 예: $0.00082)이 0.00으로
    // 잘려 저장된다 - 표시 문제가 아니라 실제 정보 손실. ProxyService.priceOf()가 소수점 8자리로
    // 계산하므로(divide(..., 8, HALF_UP)) 컬럼도 scale=8로 맞춘다. 실 API 키로 검증하다가 발견한 문제.
    @Column(precision = 19, scale = 8)
    private BigDecimal cost;
    private Long latencyMs;
    private Integer statusCode;
    private boolean cacheHit;
    private LocalDateTime createdAt;

    @Builder
    public RequestLog(User user, Team team, ApiKey apiKey, String model, String providerName,
                      Integer inputTokens, Integer outputTokens, BigDecimal cost,
                      Long latencyMs, Integer statusCode, boolean cacheHit) {
        this.user = user;
        this.team = team;
        this.apiKey = apiKey;
        this.model = model;
        this.providerName = providerName;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cost = cost;
        this.latencyMs = latencyMs;
        this.statusCode = statusCode;
        this.cacheHit = cacheHit;
        this.createdAt = LocalDateTime.now();
    }
}
