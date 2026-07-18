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
}
