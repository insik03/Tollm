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
    private Security security = new Security();

    // [BYOK] 사용자 프로바이더 키 암호화용 설정
    @Getter
    @Setter
    public static class Security {
        // 사용자 등록 키(sk-...)를 AES-GCM으로 암호화할 때 쓰는 비밀. prod에서는 반드시 환경변수로
        // 주입한다(fail-fast). 이 값이 바뀌면 기존에 저장된 암호문을 복호화할 수 없게 되니 주의.
        private String byokEncryptionKey;
    }

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

        // [성능/안정성 수정] 외부 LLM 동시 호출 상한(격벽, bulkhead). relay()는 외부 응답을
        // 블로킹으로 최대 60초 기다리므로, 프로바이더가 느려지면 톰캣 워커 스레드(기본 200)가
        // 전부 LLM 대기로 물려 로그인/대시보드 같은 무관한 요청까지 먹통이 된다. 동시 외부 호출을
        // 이 값으로 제한하고 초과분은 즉시 503으로 되돌려(스레드를 붙잡지 않고 빠르게 반환),
        // 톰캣 스레드 일부를 항상 다른 엔드포인트용으로 남겨둔다. 톰캣 max-threads보다 작게 잡는다.
        private int maxConcurrentUpstream = 100;
    }
}
