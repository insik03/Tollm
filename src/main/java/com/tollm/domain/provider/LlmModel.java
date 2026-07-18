package com.tollm.domain.provider;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

// 모델별 단가표. 비용 계산의 기준 데이터
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LlmModel {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id", nullable = false)
    private Provider provider;

    @Column(nullable = false)
    private String name;  // "gpt-4o-mini", "claude-haiku-4-5" 등

    // 1M 토큰당 단가 (USD)
    private BigDecimal inputPricePer1m;
    private BigDecimal outputPricePer1m;

    @Builder
    public LlmModel(Provider provider, String name,
                    BigDecimal inputPricePer1m, BigDecimal outputPricePer1m) {
        this.provider = provider;
        this.name = name;
        this.inputPricePer1m = inputPricePer1m;
        this.outputPricePer1m = outputPricePer1m;
    }
}
