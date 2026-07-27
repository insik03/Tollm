package com.tollm.domain.proxy.client;

// 모든 프로바이더 클라이언트의 공통 계약.
// 입출력은 항상 "OpenAI 호환 JSON" — 형식 차이는 각 구현체가 내부에서 흡수한다
public interface LlmClient {

    String providerName();

    // [BYOK] 요청마다 사용할 프로바이더 키를 인자로 받는다. "어떤 키로 나갈지"의 결정(개인/팀 등록 키)은
    // ProxyService가 하고, 여기선 받은 키를 그대로 쓴다. 순수 BYOK라 서버 공용키 폴백은 없다.
    String chat(String openAiFormatJson, String apiKey);
}
