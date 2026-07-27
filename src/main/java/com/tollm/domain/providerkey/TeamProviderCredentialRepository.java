package com.tollm.domain.providerkey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamProviderCredentialRepository extends JpaRepository<TeamProviderCredential, Long> {

    Optional<TeamProviderCredential> findByTeamIdAndProvider(Long teamId, String provider);

    List<TeamProviderCredential> findByTeamId(Long teamId);
}
