package com.tollm.domain.provider;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// ProxyService의 비용 계산(단가표 조회)에 필요해 2주차에 신설. LlmModel 자체는 1주차부터 있던 엔티티.
public interface LlmModelRepository extends JpaRepository<LlmModel, Long> {
    Optional<LlmModel> findByName(String name);
}
