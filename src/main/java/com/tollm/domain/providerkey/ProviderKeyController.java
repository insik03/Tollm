package com.tollm.domain.providerkey;

import com.tollm.domain.providerkey.dto.ProviderKeyRegisterRequest;
import com.tollm.domain.providerkey.dto.ProviderKeySummary;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// [BYOK] 사용자가 자기 프로바이더 키(sk-...)를 등록/조회/삭제하는 API.
// JwtAuthFilter가 /provider-keys 를 보호하므로 로그인한 사용자만 접근 가능(userId는 필터가 넣어줌).
@RestController
@RequestMapping("/provider-keys")
@RequiredArgsConstructor
public class ProviderKeyController {

    private final ProviderKeyService providerKeyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderKeySummary register(@RequestAttribute("userId") Long userId,
                                       @Valid @RequestBody ProviderKeyRegisterRequest request) {
        return providerKeyService.register(userId, request.provider(), request.apiKey());
    }

    @GetMapping
    public List<ProviderKeySummary> myKeys(@RequestAttribute("userId") Long userId) {
        return providerKeyService.myKeys(userId);
    }

    @DeleteMapping("/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestAttribute("userId") Long userId, @PathVariable String provider) {
        providerKeyService.delete(userId, provider);
    }
}
