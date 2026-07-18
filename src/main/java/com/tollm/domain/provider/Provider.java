package com.tollm.domain.provider;

import jakarta.persistence.*;
import lombok.*;

// LLM 제공사 (OpenAI, Anthropic 등)
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Provider {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;      // "openai", "anthropic"

    private String baseUrl;

    private boolean enabled;  // 장애/소진 시 false -> 폴백 라우팅 대상에서 제외

    @Builder
    public Provider(String name, String baseUrl) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.enabled = true;
    }
}
