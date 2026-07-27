package com.tollm.domain.providerkey;

import com.tollm.domain.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// [BYOK - Bring Your Own Key] 사용자가 등록한 프로바이더 키.
// 값(sk-...)은 절대 평문으로 두지 않고 CryptoService로 암호화한 encryptedKey만 저장한다.
// keyPreview는 대시보드에서 "어떤 키인지" 알아볼 수 있게 앞뒤 일부만 마스킹한 표시용 문자열.
// (user, provider) 한 쌍당 최대 1개 - 같은 프로바이더 키를 다시 등록하면 교체한다.
@Entity
@Table(name = "provider_credential",
        uniqueConstraints = @UniqueConstraint(name = "uk_provider_credential_user_provider",
                columnNames = {"user_id", "provider"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProviderCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String provider; // "openai" | "anthropic"

    @Column(nullable = false, length = 1024)
    private String encryptedKey; // AES-GCM 암호문(base64)

    @Column(nullable = false)
    private String keyPreview; // 표시용 마스킹 (예: "sk-proj...wXaM")

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public ProviderCredential(User user, String provider, String encryptedKey, String keyPreview) {
        this.user = user;
        this.provider = provider;
        this.encryptedKey = encryptedKey;
        this.keyPreview = keyPreview;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    // 키를 다시 등록하면 기존 행을 교체(update)한다 - 행이 늘어나지 않게
    public void replace(String encryptedKey, String keyPreview) {
        this.encryptedKey = encryptedKey;
        this.keyPreview = keyPreview;
        this.createdAt = LocalDateTime.now();
    }
}
