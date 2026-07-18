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
