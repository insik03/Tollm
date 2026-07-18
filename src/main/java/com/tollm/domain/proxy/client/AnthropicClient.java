package com.tollm.domain.proxy.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tollm.global.config.TollmProperties;
import com.tollm.global.error.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

// Anthropic은 요청/응답 형식이 다르다 → OpenAI 형식으로 번역하는 "어댑터"
// (클라이언트는 프로바이더가 뭐든 OpenAI 형식 하나만 알면 되게)
@Component
public class AnthropicClient implements LlmClient {

    private final RestClient restClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public AnthropicClient(TollmProperties properties, ClientHttpRequestFactory llmRequestFactory,
                           ObjectMapper objectMapper) {
        TollmProperties.Provider p = properties.getProviders().get("anthropic");
        this.restClient = RestClient.builder()
                .baseUrl(p.getBaseUrl())
                .requestFactory(llmRequestFactory)
                .build();
        this.apiKey = p.getApiKey();
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerName() {
        return "anthropic";
    }

    @Override
    public String chat(String openAiFormatJson) {
        try {
            String anthropicRequest = toAnthropicRequest(openAiFormatJson);
            String anthropicResponse = restClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)                    // Anthropic은 Bearer가 아니라 x-api-key 헤더
                    .header("anthropic-version", "2023-06-01")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(anthropicRequest)
                    .retrieve()
                    .body(String.class);
            return toOpenAiResponse(anthropicResponse);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("요청 본문 JSON 형식이 올바르지 않습니다");
        } catch (RestClientResponseException e) {
            throw ApiException.badGateway("Anthropic 응답 오류: HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            throw ApiException.badGateway("Anthropic 연결 실패 (타임아웃/네트워크)");
        }
    }

    // OpenAI 형식 → Anthropic 형식
    // 차이 1: system 역할이 messages 배열이 아니라 별도 "system" 필드
    // 차이 2: max_tokens가 필수
    private String toAnthropicRequest(String openAiFormatJson) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(openAiFormatJson);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", root.path("model").asText());
        request.put("max_tokens", root.path("max_tokens").asInt(1024));

        StringBuilder system = new StringBuilder();
        ArrayNode messages = request.putArray("messages");
        for (JsonNode m : root.path("messages")) {
            if ("system".equals(m.path("role").asText())) {
                system.append(m.path("content").asText());
            } else {
                ObjectNode msg = messages.addObject();
                msg.put("role", m.path("role").asText());
                msg.put("content", m.path("content").asText());
            }
        }
        if (!system.isEmpty()) {
            request.put("system", system.toString());
        }
        return request.toString();
    }

    // Anthropic 형식 → OpenAI 형식 (choices/usage 구조로 재조립)
    private String toOpenAiResponse(String anthropicJson) throws JsonProcessingException {
        JsonNode a = objectMapper.readTree(anthropicJson);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("id", a.path("id").asText());
        out.put("object", "chat.completion");
        out.put("model", a.path("model").asText());

        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", a.path("content").path(0).path("text").asText());

        ObjectNode choice = objectMapper.createObjectNode();
        choice.put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", "stop");
        out.putArray("choices").add(choice);

        int inputTokens = a.path("usage").path("input_tokens").asInt();
        int outputTokens = a.path("usage").path("output_tokens").asInt();
        ObjectNode usage = out.putObject("usage");
        usage.put("prompt_tokens", inputTokens);
        usage.put("completion_tokens", outputTokens);
        usage.put("total_tokens", inputTokens + outputTokens);

        return out.toString();
    }
}
