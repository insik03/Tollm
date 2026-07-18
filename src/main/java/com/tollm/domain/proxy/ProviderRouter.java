package com.tollm.domain.proxy;

import com.tollm.domain.proxy.client.AnthropicClient;
import com.tollm.domain.proxy.client.LlmClient;
import com.tollm.domain.proxy.client.OpenAiClient;
import com.tollm.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProviderRouter {

    private final OpenAiClient openAiClient;
    private final AnthropicClient anthropicClient;

    // 모델 이름 규칙으로 프로바이더 결정: gpt-*/o* → openai, claude* → anthropic
    // TODO [확장 - 2주차] 폴백: 주 프로바이더 5xx/타임아웃 시 enabled인 다른 프로바이더로 재시도
    public LlmClient route(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw ApiException.badRequest("model 필드는 필수입니다");
        }
        if (modelName.startsWith("gpt-") || modelName.startsWith("o")) {
            return openAiClient;
        }
        if (modelName.startsWith("claude")) {
            return anthropicClient;
        }
        throw ApiException.badRequest("지원하지 않는 모델입니다: " + modelName);
    }
}
