package com.tollm.domain.proxy.client;

// 모든 프로바이더 클라이언트의 공통 계약.
// 입출력은 항상 "OpenAI 호환 JSON" — 형식 차이는 각 구현체가 내부에서 흡수한다
public interface LlmClient {

    String providerName();

    String chat(String openAiFormatJson);
}
