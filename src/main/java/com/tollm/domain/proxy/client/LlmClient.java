package com.tollm.domain.proxy.client;

// 모든 프로바이더 클라이언트의 공통 계약.
// 입출력은 항상 "OpenAI 호환 JSON" — 형식 차이는 각 구현체가 내부에서 흡수한다
public interface LlmClient {

    String providerName();

    // [BYOK] 요청마다 사용할 프로바이더 키를 인자로 받는다. 예전엔 클라이언트가 서버 공용키를
    // 고정으로 들고 있었지만, 사용자가 자기 키를 등록(BYOK)하면 그 키로 나가야 하므로, "어떤 키로
    // 나갈지"의 결정은 ProxyService가 하고 여기선 받은 키를 그대로 쓴다.
    String chat(String openAiFormatJson, String apiKey);

    // 서버 공용키(설정으로 주입된 값). BYOK 미등록 사용자는 ProxyService가 이 값으로 폴백한다.
    String defaultApiKey();
}
