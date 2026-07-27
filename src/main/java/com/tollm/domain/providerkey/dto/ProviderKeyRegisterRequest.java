package com.tollm.domain.providerkey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 사용자가 자기 프로바이더 키를 등록할 때의 요청 본문
public record ProviderKeyRegisterRequest(
        @NotBlank(message = "provider는 필수입니다 (openai 또는 anthropic)") String provider,
        // [검수 #5] 상한이 없으면 아주 긴 입력의 암호문이 encrypted_key varchar(1024)를 넘겨 insert가
        // 500으로 실패할 수 있다. 실제 프로바이더 키는 수백 자 이내라 512자면 충분하다.
        @NotBlank(message = "apiKey는 필수입니다") @Size(max = 512, message = "apiKey가 너무 깁니다") String apiKey
) {
}
