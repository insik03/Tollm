package com.tollm.domain.apikey;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {
    Optional<ApiKey> findByKeyHashAndStatus(String keyHash, ApiKey.Status status);
    List<ApiKey> findByUserId(Long userId);
}
