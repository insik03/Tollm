package com.tollm.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    // 타임아웃 필수: 외부 LLM API가 응답을 안 주면 톰캣 스레드가 하나씩 물려서
    // 결국 서버 전체가 신규 요청을 못 받는다 (스레드 풀 고갈)
    @Bean
    public ClientHttpRequestFactory llmRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));   // 연결 수립 대기
        factory.setReadTimeout(Duration.ofSeconds(60));     // LLM 생성은 느릴 수 있어 넉넉히
        return factory;
    }
}
