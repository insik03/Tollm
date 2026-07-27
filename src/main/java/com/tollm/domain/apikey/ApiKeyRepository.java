package com.tollm.domain.apikey;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {
    Optional<ApiKey> findByKeyHashAndStatus(String keyHash, ApiKey.Status status);
    List<ApiKey> findByUserId(Long userId);
    List<ApiKey> findByTeamId(Long teamId);
    // 특정 팀에서 특정 멤버가 발급한 팀 키 - 탈퇴/강제퇴장 시 그 멤버 키만 폐기하기 위함
    List<ApiKey> findByTeamIdAndUserId(Long teamId, Long userId);
}
