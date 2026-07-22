package com.tollm.domain.usage;

import com.tollm.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

// 요청마다 1행씩 쌓이는 대용량 테이블 -> 인덱스 설계가 성능 학습 포인트
@Entity
@Table(indexes = {
    @Index(name = "idx_log_user_created", columnList = "user_id, createdAt")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

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
    public RequestLog(User user, String model, String providerName,
                      Integer inputTokens, Integer outputTokens, BigDecimal cost,
                      Long latencyMs, Integer statusCode, boolean cacheHit) {
        this.user = user;
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
