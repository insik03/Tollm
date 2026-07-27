package com.tollm.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

// [성능 수정] 매 /v1 요청마다 (1) API 키 조회 (2) 단가표(LlmModel) 조회로 DB를 두 번 왕복했다.
// 둘 다 사실상 준정적 데이터(키 폐기는 드물고 단가표는 시드 데이터)라, 짧은 TTL 캐시로 캐시 히트
// 경로의 DB 부하를 크게 줄인다. 캐시별로 성격이 달라 TTL/크기를 따로 준다.
@Configuration
@EnableCaching
public class CacheConfig {

    // API 키 인증 결과: 폐기를 최대 이 시간만큼 늦게 반영하는 트레이드오프를 감수하고 30초로 짧게 잡는다.
    // (폐기 즉시 반영이 필요하면 ApiKeyService.revoke가 캐시를 통째로 비운다 - @CacheEvict allEntries)
    public static final String API_KEY_AUTH = "apiKeyAuth";
    // 모델 단가: 관리자 단가 변경 API가 없어 사실상 부팅 시 시드 이후 불변. 길게 잡아도 안전하다.
    public static final String MODEL_PRICING = "modelPricing";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache(API_KEY_AUTH, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(10_000)
                .build());
        manager.registerCustomCache(MODEL_PRICING, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(1))
                .maximumSize(1_000)
                .build());
        // [검수] 트랜잭션 인식 래핑: @Transactional 안에서 @CacheEvict가 "커밋 전"에 도는 순서가 되면,
        // 커밋 전 들어온 동시 요청이 아직 ACTIVE인 (폐기 전) 키를 다시 캐시해 최대 TTL(30초)간 계속
        // 인증되는 창이 생긴다(키 폐기/팀 탈퇴·해지 시). 프록시로 감싸면 캐시 쓰기/삭제가 커밋 이후에만
        // 반영되어 이 창을 없앤다.
        return new TransactionAwareCacheManagerProxy(manager);
    }
}
