package com.tollm.domain.proxy.client;

import com.tollm.global.config.TollmProperties;
import com.tollm.global.error.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

// OpenAI는 우리 API와 형식이 같으므로 "통과(passthrough)" — 키만 서버 것으로 바꿔 전달
@Component
public class OpenAiClient implements LlmClient {

    private final RestClient restClient;
    private final String apiKey;

    public OpenAiClient(TollmProperties properties, ClientHttpRequestFactory llmRequestFactory) {
        TollmProperties.Provider p = properties.getProviders().get("openai");
        this.restClient = RestClient.builder()
                .baseUrl(p.getBaseUrl())
                .requestFactory(llmRequestFactory)
                .build();
        this.apiKey = p.getApiKey();
    }

    @Override
    public String providerName() {
        return "openai";
    }

    @Override
    public String chat(String openAiFormatJson) {
        try {
            return restClient.post()
                    .uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey) // 클라이언트의 tlm_ 키가 아니라 서버가 보관한 원본 키
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(openAiFormatJson)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw ApiException.badGateway("OpenAI 응답 오류: HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            throw ApiException.badGateway("OpenAI 연결 실패 (타임아웃/네트워크)");
        }
    }
}
