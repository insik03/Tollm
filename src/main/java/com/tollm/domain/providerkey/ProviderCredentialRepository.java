package com.tollm.domain.providerkey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProviderCredentialRepository extends JpaRepository<ProviderCredential, Long> {

    Optional<ProviderCredential> findByUserIdAndProvider(Long userId, String provider);

    List<ProviderCredential> findByUserId(Long userId);
}
