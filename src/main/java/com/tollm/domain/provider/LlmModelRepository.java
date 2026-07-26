package com.tollm.domain.provider;

import com.tollm.global.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// ProxyService의 비용 계산(단가표 조회)에 필요해 2주차에 신설. LlmModel 자체는 1주차부터 있던 엔티티.
public interface LlmModelRepository extends JpaRepository<LlmModel, Long> {

    // [성능 수정] 단가표는 사실상 부팅 시 시드 이후 불변(관리자 단가 변경 API 없음)이라, 매 /v1 요청마다
    // DB를 치지 않도록 캐시한다(TTL 1시간, CacheConfig.MODEL_PRICING). 캐시에 담기는 LlmModel의
    // 지연 로딩 필드(provider)는 ProxyService 비용 계산에서 건드리지 않으므로 안전하다.
    //
    // unless: "없음(빈 Optional)"은 캐시하지 않는다. DataInitializer가 시드 전에 "이 모델 있나?"를
    // 이 메서드로 확인하는데, 빈 DB(운영 첫 부팅)에서 이때 "없음"이 캐시되면, 바로 뒤 INSERT로 행이
    // 생겨도 캐시엔 "없음"이 남아 모든 요청이 "단가 정보 없음"으로 실패한다(cache poisoning).
    // Spring Cache는 Optional을 언랩하므로 #result는 LlmModel 또는 null이다.
    @Cacheable(cacheNames = CacheConfig.MODEL_PRICING, key = "#name", unless = "#result == null")
    Optional<LlmModel> findByName(String name);
}
