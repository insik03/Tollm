package com.tollm.domain.providerkey.dto;

import com.tollm.domain.providerkey.ProviderCredential;

import java.time.LocalDateTime;

// 등록된 프로바이더 키의 표시용 정보. 절대 원문/암호문을 노출하지 않고 마스킹(keyPreview)만 준다.
public record ProviderKeySummary(String provider, String keyPreview, LocalDateTime registeredAt) {

    public static ProviderKeySummary from(ProviderCredential c) {
        return new ProviderKeySummary(c.getProvider(), c.getKeyPreview(), c.getCreatedAt());
    }
}
