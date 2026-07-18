package com.tollm.global.logging;

import org.springframework.stereotype.Component;

// 7주차에 배운 AOP 활용 포인트
@Component
// @Aspect
public class RequestLogAspect {

    // TODO [1주차] ProxyService.relay() 주변(@Around)에서
    //  시작/종료 시각 측정 -> latencyMs 계산해 로그 저장에 전달
    //  (비용/토큰 수는 응답 본문에서 나오므로 서비스 내부에서 처리해도 됨.
    //   무엇을 AOP로 빼고 무엇을 서비스에 둘지 스스로 근거를 세워볼 것)
}
