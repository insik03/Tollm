package com.tollm.domain.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.tollm.global.auth.HashUtils;
import com.tollm.global.config.TollmProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class ResponseCacheService {

    // 필드 구분자. normalize()가 content 내부의 연속 공백을 이미 한 칸으로 압축하므로,
    // 구분자로 쓰는 공백과 실제 내용의 공백이 섞여도 같은 입력에는 항상 같은 해시가 나온다는
    // 결정성(determinism)만 지키면 충분하고, 경계가 다른 두 요청이 우연히 같은 해시로
    // 충돌할 확률은 SHA-256 특성상 무시할 수 있는 수준이다.
    private static final String SEP = " ";
    private static final String KEY_PREFIX = "cache:";

    private final StringRedisTemplate redisTemplate;
    private final TollmProperties properties;

    // 캐시 키 생성 책임을 ProxyService가 아니라 여기 두는 이유:
    // "무엇을 같은 요청으로 취급할지"(정규화 규칙, 해시 알고리즘)는 캐시의 구현 세부사항이다.
    // ProxyService는 이미 파싱해 둔 JsonNode(모델 추출에 쓴 것과 동일)만 넘기고,
    // 정규화 규칙이 바뀌어도 ProxyService는 건드릴 필요가 없게 한다.
    //
    // [보안 결정] userId를 캐시 키에 포함한다 (근거 - 01-backend-design.md/progress-week2.md 상세):
    // 포함하지 않으면 서로 다른 사용자가 우연히 동일한 model+messages를 보냈을 때
    // 한 사용자의 요청으로 만들어진 캐시를 다른 사용자가 그대로 받는 "크로스오버"가 생긴다.
    // 이 게이트웨이는 messages 내용(사용자가 어떤 정보를 프롬프트에 적을지)을 통제하지 않으므로
    // 캐시 히트율이 다소 낮아지더라도 사용자 간 응답 크로스오버 가능성을 원천 차단하는 쪽을 기본값으로 택했다.
    // (Team 기능[확장] 도입 시 "팀 단위 공유 캐시"로 범위를 넓히는 것은 그때 별도 검토)
    public String buildKey(Long userId, JsonNode root) {
        String model = root.path("model").asText("");

        StringBuilder messagesPart = new StringBuilder();
        for (JsonNode message : root.path("messages")) {
            String role = message.path("role").asText("");
            String content = normalize(message.path("content").asText(""));
            messagesPart.append(role).append(':').append(content).append(SEP);
        }

        String raw = userId + SEP + model + SEP + messagesPart;
        return KEY_PREFIX + HashUtils.sha256(raw);
    }

    // 공백 정리: 앞뒤 공백 제거 + 연속 공백/개행을 한 칸으로 압축.
    // 의미상 같은 프롬프트가 줄바꿈/들여쓰기 차이만으로 캐시 미스가 되는 것을 막는다.
    // (여기서 말하는 "exact 캐시"는 어디까지나 정규화된 텍스트의 완전 일치를 뜻하며,
    //  의미적으로 유사한 다른 표현까지 매칭하는 시맨틱 캐시는 non-goal이라 다루지 않는다)
    private String normalize(String content) {
        return content.strip().replaceAll("\\s+", " ");
    }

    public String get(String cacheKey) {
        return redisTemplate.opsForValue().get(cacheKey);
    }

    public void put(String cacheKey, String response) {
        redisTemplate.opsForValue().set(cacheKey, response, Duration.ofSeconds(properties.getCache().getTtlSeconds()));
    }
}
