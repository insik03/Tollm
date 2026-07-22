package com.tollm.domain.apikey;

import com.tollm.domain.apikey.dto.ApiKeyIssueResponse;
import com.tollm.domain.apikey.dto.ApiKeySummary;
import com.tollm.domain.usage.dto.UsageSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    // userId는 JwtAuthFilter가 토큰을 검증해서 request attribute에 넣어준 값
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyIssueResponse issue(@RequestAttribute("userId") Long userId) {
        return apiKeyService.issue(userId);
    }

    @GetMapping
    public List<ApiKeySummary> myKeys(@RequestAttribute("userId") Long userId) {
        return apiKeyService.myKeys(userId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        apiKeyService.revoke(userId, id);
    }

    // 이 키 "하나"의 사용량 - /usage/me(내 모든 개인 키 합산)와 별개로, 키를 여러 개 발급받았을 때
    // 어느 키가 얼마나 썼는지 구분하고 싶다는 요구로 추가
    @GetMapping("/{id}/usage")
    public UsageSummaryResponse keyUsage(
            @RequestAttribute("userId") Long userId, @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return apiKeyService.keyUsage(userId, id, from, to);
    }
}
