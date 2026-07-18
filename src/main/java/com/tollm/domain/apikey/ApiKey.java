package com.tollm.domain.apikey;

import com.tollm.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiKey {

    public enum Status { ACTIVE, REVOKED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 원문 키는 저장하지 않음. SHA-256 해시만 저장 (발급 시 1회만 원문 노출)
    @Column(nullable = false, unique = true)
    private String keyHash;

    // 식별용 접두어 예: tlm_a1b2 (목록에서 어떤 키인지 구분용)
    @Column(nullable = false)
    private String prefix;

    @Enumerated(EnumType.STRING)
    private Status status;

    private LocalDateTime createdAt;
    private LocalDateTime expiredAt;

    @Builder
    public ApiKey(User user, String keyHash, String prefix, LocalDateTime expiredAt) {
        this.user = user;
        this.keyHash = keyHash;
        this.prefix = prefix;
        this.status = Status.ACTIVE;
        this.createdAt = LocalDateTime.now();
        this.expiredAt = expiredAt;
    }

    public void revoke() { this.status = Status.REVOKED; }
}
