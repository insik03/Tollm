package com.tollm.domain.providerkey.dto;

import jakarta.validation.constraints.NotBlank;

// 사용자가 자기 프로바이더 키를 등록할 때의 요청 본문
public record ProviderKeyRegisterRequest(
        @NotBlank(message = "provider는 필수입니다 (openai 또는 anthropic)") String provider,
        @NotBlank(message = "apiKey는 필수입니다") String apiKey
) {
}
