package com.tollm.global.logging;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

// 관측성(observability) 목적의 횡단 관심사 전담: ProxyService.relay() 호출마다 반복되는
// "시작/종료 로그 남기기"를 어노테이션 하나로 모든 호출 지점에 일괄 적용한다.
//
// [AOP로 뺀 것과 서비스에 둔 것 - 근거]
// 이 Aspect는 relay() 실행 전/후의 벽시계 시간을 재서 애플리케이션 로그(slf4j)에만 남긴다.
// RequestLog.latencyMs(프로바이더 왕복 시간)는 여기서 채우지 않고 ProxyService 내부에서 직접
// 측정한다. 이유: @Around는 relay()의 "실행 전/실행 후"만 관찰할 수 있는데, RequestLog 저장은
// relay() 내부(외부 호출 직후, 아직 메서드가 끝나기 전) 시점에 일어난다. 만약 Aspect가 잰
// latencyMs를 RequestLog에 반영하려면 relay()가 "끝난 뒤" 이미 저장된 로그 행을 다시 찾아
// UPDATE해야 하는데, 이는 (1) 쓰기를 두 번 하는 비용, (2) 그 사이 동시 요청이 끼어들면 엉뚱한
// 행을 갱신할 레이스 컨디션을 만든다. 반대로 "메서드 실행 자체를 관찰하는 로그"는 반환값/저장
// 시점과 무관하므로 AOP로 깔끔하게 분리할 수 있다 - 이것이 AOP가 잘 맞는 "순수 횡단 관심사"와,
// 메서드 내부 상태(비용 계산에 바로 쓰이는 latencyMs)를 다루는 "비즈니스 로직"의 경계라고 판단했다.
@Aspect
@Component
@Slf4j
public class RequestLogAspect {

    @Around("execution(* com.tollm.domain.proxy.ProxyService.relay(..))")
    public Object logRelay(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            log.info("relay() 처리 완료 - {}ms", System.currentTimeMillis() - start);
            return result;
        } catch (Throwable e) {
            log.warn("relay() 처리 실패 - {}ms, cause={}", System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }
}
