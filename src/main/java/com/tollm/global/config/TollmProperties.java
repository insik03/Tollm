package com.tollm.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

// application.yml의 tollm.* 값을 타입 안전하게 묶어서 주입받는 클래스.
// @Value를 여기저기 흩뿌리는 대신 설정의 형태가 한 클래스에 문서화된다
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "tollm")
public class TollmProperties {

    // key: "openai", "anthropic" (yml의 tollm.providers 아래 이름)
    private Map<String, Provider> providers = new HashMap<>();
    private RateLimit rateLimit = new RateLimit();
    private Cache cache = new Cache();
    private Proxy proxy = new Proxy();

    @Getter
    @Setter
    public static class Provider {
        private String baseUrl;
        private String apiKey;
    }

    @Getter
    @Setter
    public static class RateLimit {
        private int capacity;
        private int refillPerSec;
    }

    // exact 응답 캐시 설정 (2주차)
    @Getter
    @Setter
    public static class Cache {
        // 기본 1시간: 응답 캐시는 짧게 잡으면 히트율이 낮고, 길게 잡으면 모델 업데이트/가격 변동과
        // 무관하게 오래된 응답이 재사용될 위험이 있다. 1시간은 팀 내 반복 질의(문서 QA, 코드 리뷰 등)의
        // 재사용 효과를 살리면서도 "너무 오래된 답"이 나올 위험을 낮게 유지하는 절충값으로 선택했다
        private long ttlSeconds = 3600;
    }

    // [보안 수정 SEC-02] /v1/** 프록시 요청 본문 크기 제한
    @Getter
    @Setter
    public static class Proxy {
        // 기본 1MB: LLM 프롬프트는 대부분 수십 KB 이내이고, 시스템 프롬프트+대화 이력을 넉넉히
        // 잡아도 1MB면 충분하다고 판단했다. Content-Length 헤더 기준으로 사전에 거부하므로
        // 초과 요청은 본문을 읽기 전에 413으로 차단된다(RequestSizeLimitFilter)
        private long maxRequestBodyBytes = 1_048_576;
    }
}
