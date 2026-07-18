package com.tollm.domain.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollm.domain.proxy.client.LlmClient;
import com.tollm.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProxyService {

    private final ProviderRouter providerRouter;
    private final ObjectMapper objectMapper;

    // 흐름 (플로우차트와 동일한 순서):
    // TODO [2주차] 1. rateLimitService.check(userId) - 초과 시 429 예외
    // TODO [2주차] 2. 쿼터 확인 - 월 예산 초과 시 429
    // TODO [2주차] 3. responseCacheService.get(요청) - 히트 시 즉시 반환
    public String relay(Long userId, String body) {
        String model = extractModel(body);                 // 4. 모델명으로 프로바이더 결정
        LlmClient client = providerRouter.route(model);
        String response = client.chat(body);               // 5. 외부 API 호출 (원본 키는 서버 설정에서)
        // TODO [④] 6. 응답에서 토큰 수 추출 -> 비용 계산 -> RequestLog 저장
        // TODO [2주차] 7. responseCacheService.put(요청, 응답)
        return response;
    }

    private String extractModel(String body) {
        try {
            String model = objectMapper.readTree(body).path("model").asText(null);
            if (model == null) {
                throw ApiException.badRequest("model 필드는 필수입니다");
            }
            return model;
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("요청 본문이 올바른 JSON이 아닙니다");
        }
    }
}
