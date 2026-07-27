package com.tollm.domain.providerkey;

import com.tollm.domain.team.Team;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// [BYOK - 팀] 팀 공용 프로바이더 키. 개인용 ProviderCredential를 팀 단위로 복제한 것
// (UsageQuota↔TeamUsageQuota와 같은 "병렬 엔티티" 패턴). 팀 요청은 이 키로 나가고,
// 서버 공용키로 폴백하지 않는다(검수 #9 근본 해결). (team, provider) 한 쌍당 1개.
@Entity
@Table(name = "team_provider_credential",
        uniqueConstraints = @UniqueConstraint(name = "uk_team_provider_credential_team_provider",
                columnNames = {"team_id", "provider"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamProviderCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(nullable = false)
    private String provider; // "openai" | "anthropic"

    @Column(nullable = false, length = 1024)
    private String encryptedKey; // AES-GCM 암호문(base64)

    @Column(nullable = false)
    private String keyPreview; // 표시용 마스킹

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public TeamProviderCredential(Team team, String provider, String encryptedKey, String keyPreview) {
        this.team = team;
        this.provider = provider;
        this.encryptedKey = encryptedKey;
        this.keyPreview = keyPreview;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public void replace(String encryptedKey, String keyPreview) {
        this.encryptedKey = encryptedKey;
        this.keyPreview = keyPreview;
        this.createdAt = LocalDateTime.now();
    }
}
