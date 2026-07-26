package com.tollm.domain.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollm.global.auth.HashUtils;
import com.tollm.global.config.TollmProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class ResponseCacheService {

    private static final String SEP = " ";
    private static final String KEY_PREFIX = "cache:";

    private final StringRedisTemplate redisTemplate;
    private final TollmProperties properties;
    private final ObjectMapper objectMapper;

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
        return buildKeyInternal(String.valueOf(userId), root);
    }

    // 팀 키 요청 전용 (add-on) - "team:{id}"로 구분해서 개인 캐시 항목과 절대 안 섞인다.
    // 팀원끼리는 캐시를 공유하는 게 자연스럽다는 판단(같은 팀이 같은 질문을 반복하면 캐시 재사용 이득) -
    // 근거는 이 파일 buildKey() 주석의 "Team 기능 도입 시 별도 검토" 항목을 실제로 적용한 것.
    public String buildKeyForTeam(Long teamId, JsonNode root) {
        return buildKeyInternal("team:" + teamId, root);
    }

    // [정합성 수정] 예전엔 model + messages(role:content)만 키에 넣고, 그것도 content를
    // asText()로만 읽었다. 문제 두 가지:
    //  1) content가 OpenAI 표준 배열 형식([{"type":"text","text":"질문"}])이면 asText()가
    //     빈 문자열을 반환 → 서로 다른 질문이 role만 남은 같은 키로 충돌해 남의 답이 나갔다.
    //  2) 응답을 바꾸는 파라미터(temperature, max_tokens, response_format 등)가 키에서 빠져,
    //     max_tokens=50과 4000이 같은 키가 되어 잘린 응답을 캐시 히트로 받았다.
    // 그래서 "정규화된 요청 본문 전체"를 키에 넣는다. 같은 바이트 입력은 항상 같은 해시가 되고
    // (필드 순서까지 그대로 유지), 조금이라도 다른 요청은 다른 키가 되어 오응답이 원천 차단된다.
    // 트레이드오프: 공백/필드순서만 다른 의미상 같은 요청은 캐시 미스가 될 수 있으나, 이는
    // "안전한 미스"라 잘못된 히트(남의/다른 파라미터의 응답)보다 항상 낫다.
    private String buildKeyInternal(String principal, JsonNode root) {
        String canonicalBody;
        try {
            canonicalBody = objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // 이미 파싱에 성공한 JsonNode라 사실상 도달 불가하지만, 실패하면 캐시를 못 쓸 뿐
            // (매번 다른 키처럼 취급) 정확성은 유지된다.
            canonicalBody = String.valueOf(root.hashCode());
        }
        return KEY_PREFIX + HashUtils.sha256(principal + SEP + canonicalBody);
    }

    public String get(String cacheKey) {
        return redisTemplate.opsForValue().get(cacheKey);
    }

    public void put(String cacheKey, String response) {
        redisTemplate.opsForValue().set(cacheKey, response, Duration.ofSeconds(properties.getCache().getTtlSeconds()));
    }
}
