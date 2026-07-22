package com.tollm.domain.proxy;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

// 핵심 엔드포인트. OpenAI API 호환 형식으로 만들어
// 기존 AI 도구들이 base URL만 바꿔서 Tollm을 통과하게 한다
@RestController
@RequiredArgsConstructor
public class ProxyController {

    private final ProxyService proxyService;

    // body를 DTO가 아닌 String으로 받는 이유: 프록시는 형식을 해석하지 않고 "통과"가 기본.
    // OpenAI가 필드를 추가해도 우리는 코드 수정 없이 그대로 전달된다
    @PostMapping(value = "/v1/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public String chat(@RequestAttribute("userId") Long userId,
                       @RequestAttribute(value = "teamId", required = false) Long teamId,
                       @RequestBody String body) {
        return proxyService.relay(userId, teamId, body);
    }
}
