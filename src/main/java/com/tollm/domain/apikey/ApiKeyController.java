package com.tollm.domain.apikey;

import com.tollm.domain.apikey.dto.ApiKeyIssueResponse;
import com.tollm.domain.apikey.dto.ApiKeySummary;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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
}
