package com.tollm.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    // [성능 수정] 예전엔 SimpleClientHttpRequestFactory(JDK HttpURLConnection 기반)를 썼는데,
    // 이건 커넥션 풀이 없어 동시 요청이 JVM 기본 keep-alive 캐시(호스트당 5개)를 넘는 순간부터
    // 매 요청 TCP+TLS 핸드셰이크를 새로 하고 TIME_WAIT 소켓이 쌓인다(부하 테스트에서 드러남).
    // java.net.http.HttpClient는 커넥션 풀을 내장하므로, 이를 감싸는 JdkClientHttpRequestFactory로
    // 교체해 아웃바운드 커넥션을 재사용한다. 별도 의존성(Apache HttpClient 등) 추가 없이 JDK 내장으로 해결.
    //
    // 타임아웃은 여전히 필수: 외부 LLM API가 응답을 안 주면 톰캣 워커 스레드가 하나씩 물려
    // 스레드 풀이 고갈된다. connect는 HttpClient에, read는 팩토리에 설정한다.
    @Bean
    public ClientHttpRequestFactory llmRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)) // 연결 수립 대기
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(60)); // LLM 생성은 느릴 수 있어 넉넉히
        return factory;
    }
}
