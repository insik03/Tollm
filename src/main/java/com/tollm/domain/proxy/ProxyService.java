package com.tollm.domain.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollm.domain.apikey.ApiKey;
import com.tollm.domain.apikey.ApiKeyRepository;
import com.tollm.domain.provider.LlmModel;
import com.tollm.domain.provider.LlmModelRepository;
import com.tollm.domain.providerkey.ProviderKeyService;
import com.tollm.domain.proxy.client.LlmClient;
import com.tollm.domain.team.Team;
import com.tollm.domain.team.TeamRepository;
import com.tollm.domain.team.TeamUsageQuota;
import com.tollm.domain.team.TeamUsageQuotaRepository;
import com.tollm.domain.usage.RequestLog;
import com.tollm.domain.usage.RequestLogRepository;
import com.tollm.domain.usage.UsageQuota;
import com.tollm.domain.usage.UsageQuotaRepository;
import com.tollm.domain.user.UserRepository;
import com.tollm.global.config.TollmProperties;
import com.tollm.global.error.ApiException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyService {

    private static final BigDecimal PER_MILLION = BigDecimal.valueOf(1_000_000);

    private final ProviderRouter providerRouter;
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final ResponseCacheService responseCacheService;
    private final UsageQuotaRepository usageQuotaRepository;
    private final RequestLogRepository requestLogRepository;
    private final LlmModelRepository llmModelRepository;
    private final UserRepository userRepository;
    // 팀 키(add-on) 전용 - 개인 경로(teamId == null)는 위 8개 협력 객체만으로 기존과 완전히 동일하게 동작한다
    private final TeamUsageQuotaRepository teamUsageQuotaRepository;
    private final TeamRepository teamRepository;
    // "요청 1건이 어느 키로 왔는지" 기록용(add-on) - apiKeyId가 null이어도(옛 호출부) 나머지 로직은 그대로 동작한다
    private final ApiKeyRepository apiKeyRepository;
    private final TollmProperties properties;
    // [BYOK] 요청을 보낼 때 이 사용자가 등록한 프로바이더 키를 찾아 쓰기 위함
    private final ProviderKeyService providerKeyService;

    // [성능/안정성 수정] 외부 LLM 동시 호출 격벽(bulkhead). tryAcquire()로 즉시 실패시켜
    // 초과 요청이 톰캣 스레드를 붙잡지 않게 한다 (근거는 TollmProperties.Proxy 주석 참고).
    private Semaphore upstreamBulkhead;

    @PostConstruct
    void initBulkhead() {
        upstreamBulkhead = new Semaphore(properties.getProxy().getMaxConcurrentUpstream());
    }

    // 흐름 (README 플로우차트 + SEC-03 보안 수정 반영):
    // 1. rateLimitService.tryConsume - 실패 시 429
    // 2. 쿼터(UsageQuota) 확인 - 월 한도 초과 시 429
    // 3. 단가표(LlmModel) 등록 여부 확인 - 미등록 모델은 400 (SEC-03, 외부 호출 전에 차단)
    // 4. responseCacheService.get() - 히트 시 즉시 반환 (비용 0, cacheHit=true로 로깅)
    // 5. 프로바이더 라우팅 (외부 LLM 호출)
    // 6. 응답에서 토큰 수 추출 -> 단가표 기반 비용 계산 -> RequestLog 저장 -> 쿼터 누적
    // 7. responseCacheService.put()
    //
    // 이 메서드 전체에 @Transactional을 걸지 않은 이유: 4단계(client.chat)는 외부 LLM API 호출이라
    // 최대 60초(RestClient read timeout, 1주차 결정)까지 걸릴 수 있다. 여기에 트랜잭션을 걸면
    // 그 시간 동안 DB 커넥션 풀에서 커넥션 하나를 계속 붙들게 되어, 동시 요청이 몰릴 때
    // "외부 API가 느려서 우리 DB 커넥션 풀까지 고갈되는" 상황이 생길 수 있다(1주차 RestClient
    // 타임아웃 결정과 같은 맥락의 문제). 그래서 쿼터 조회/리셋, 로그 저장, 쿼터 누적을 각각
    // 리포지토리 메서드 단위의 짧은 자체 트랜잭션으로 나눠 처리한다.

    // 기존 호출부(1주차~2주차 코드, ProxyServiceTest 대부분) 무변경 - 개인 키 경로 그대로 위임
    public String relay(Long userId, String body) {
        return relay(userId, null, body);
    }

    // 팀 키(add-on) 테스트 호출부 무변경 - apiKeyId 없이도 그대로 동작(RequestLog.apiKey만 null로 남음)
    public String relay(Long userId, Long teamId, String body) {
        return relay(userId, teamId, null, body);
    }

    // API 키별 사용량 구분(add-on) 지원 버전. apiKeyId가 null이면 RequestLog.apiKey만 안 채워질 뿐
    // 나머지 분기는 100% 동일하다 - "재설계"가 아니라 계속 얇게 얹는 것 (README 설계 근거 참고)
    public String relay(Long userId, Long teamId, Long apiKeyId, String body) {
        boolean isTeam = teamId != null;

        boolean allowed = isTeam ? rateLimitService.tryConsumeForTeam(teamId) : rateLimitService.tryConsume(userId);
        if (!allowed) {
            throw ApiException.tooManyRequests("요청이 너무 많습니다. 잠시 후 다시 시도하세요");
        }

        if (isTeam) {
            TeamUsageQuota quota = loadTeamQuota(teamId);
            if (quota.isExceeded()) {
                throw ApiException.tooManyRequests("이번 달 팀 사용 한도를 초과했습니다");
            }
        } else {
            UsageQuota quota = loadQuota(userId);
            if (quota.isExceeded()) {
                throw ApiException.tooManyRequests("이번 달 사용 한도를 초과했습니다");
            }
        }

        JsonNode root = parseBody(body);
        // [보안 수정 SEC-04] stream:true 요청 거부. 스트리밍 응답은 SSE 텍스트(data: {...})라
        // 우리 파서(extractTokens)가 JSON으로 못 읽어 토큰=0 → 비용=0으로 기록되고, 그 결과
        // 실제로는 프로바이더 비용이 발생했는데 UsageQuota에는 반영되지 않아 월 한도를 무제한
        // 우회할 수 있다(SEC-03과 동일한 "과금 미보장 요청 통과" 클래스). 부수적으로 SSE 원문이
        // 캐시에 들어가면 이후 비스트림 요청이 깨진 응답을 받는 오염도 생긴다. 이 게이트웨이는
        // 스트리밍을 지원하지 않으므로 외부 호출 전에 400으로 차단한다.
        if (root.path("stream").asBoolean(false)) {
            throw ApiException.badRequest("스트리밍(stream=true) 요청은 지원하지 않습니다");
        }
        String model = extractModel(root);
        // [보안 수정 SEC-03] 단가표(LlmModel)에 없는 모델은 여기서 즉시 거부한다.
        // ProviderRouter.route()는 모델명 접두어(gpt-*/claude*)만 검사하므로, 단가표에 없는
        // 하위 모델 문자열도 라우팅 자체는 통과할 수 있었다. 그 상태에서 실제 프로바이더를 호출한
        // 뒤 단가 정보가 없다고 비용만 0으로 기록하면, 실제로는 비용이 발생했는데 UsageQuota에는
        // 반영되지 않아 월 한도(쿼터)를 사실상 우회할 수 있었다(security-engineer SEC-03).
        // "게이트웨이가 과금을 보장 못 하는 요청은 통과시키지 않는다"는 원칙에 따라, 외부 호출
        // (비용 발생 시점)보다 먼저 단가 등록 여부를 확인해 badRequest로 차단한다.
        LlmModel llmModel = llmModelRepository.findByName(model)
                .orElseThrow(() -> ApiException.badRequest("단가 정보가 없는 모델입니다: " + model));
        LlmClient client = providerRouter.route(model);
        // [BYOK] 이 요청을 어떤 프로바이더 키로 내보낼지 결정한다. 캐시 조회 전에 먼저 확정해,
        // 키가 없으면 캐시 히트든 미스든 상관없이 즉시 "키를 등록하라"고 막는다.
        String providerKey = resolveProviderKey(userId, teamId, client);

        // 팀원끼리는 캐시를 공유한다(팀 단위 buildKeyForTeam) - 개인 키는 기존과 동일하게 본인만 히트
        String cacheKey = isTeam ? responseCacheService.buildKeyForTeam(teamId, root) : responseCacheService.buildKey(userId, root);
        String cached = responseCacheService.get(cacheKey);
        if (cached != null) {
            saveLog(userId, teamId, apiKeyId, model, client.providerName(), 0, 0, BigDecimal.ZERO, true, 0L);
            return cached;
        }

        // 외부 호출만 격벽으로 감싼다 (캐시 히트 경로는 위에서 이미 반환됨). 포화 시 즉시 503으로
        // 되돌려 스레드를 붙잡지 않는다 - 그래야 프록시가 막혀도 로그인/대시보드가 살아있다.
        if (!upstreamBulkhead.tryAcquire()) {
            throw ApiException.serviceUnavailable("서버가 일시적으로 혼잡합니다. 잠시 후 다시 시도하세요");
        }
        long start = System.currentTimeMillis();
        String response;
        try {
            response = client.chat(body, providerKey);
        } finally {
            upstreamBulkhead.release();
        }
        long latencyMs = System.currentTimeMillis() - start;

        int[] tokens = extractTokens(response);
        int inputTokens = tokens[0];
        int outputTokens = tokens[1];
        BigDecimal cost = calculateCost(llmModel, inputTokens, outputTokens);

        saveLog(userId, teamId, apiKeyId, model, client.providerName(), inputTokens, outputTokens, cost, false, latencyMs);
        if (isTeam) {
            teamUsageQuotaRepository.addUsage(teamId, cost);
        } else {
            usageQuotaRepository.addUsage(userId, cost);
        }

        responseCacheService.put(cacheKey, response);
        return response;
    }

    // [BYOK] 이 요청에 쓸 프로바이더 키 결정 (순수 BYOK - 서버 공용키로 폴백하지 않는다):
    // - 개인 요청: 사용자가 등록한 키. 없으면 400으로 막고 "등록하라"고 안내.
    // - 팀 요청: 팀이 등록한 키. 없으면 400. (검수 #9 - 예전엔 서버 공용키를 써서, 아무나 팀을 만들면
    //   운영자 키로 무료로 쓸 수 있는 재무적 우회가 있었다. 팀도 자기 키만 쓰게 막는다.)
    // 어느 쪽이든 운영자 키를 모르는 사람이 대신 쓰며 비용을 태우는 걸 원천 차단한다(사용자 요구사항).
    private String resolveProviderKey(Long userId, Long teamId, LlmClient client) {
        String provider = client.providerName();
        Optional<String> keyOpt;
        try {
            keyOpt = (teamId != null)
                    ? providerKeyService.decryptedTeamKeyFor(teamId, provider)
                    : providerKeyService.decryptedKeyFor(userId, provider);
        } catch (IllegalStateException e) {
            // [검수 #6] 복호화 실패(마스터키 교체/암호문 손상)를 500 대신 명확한 안내로 매핑한다.
            throw ApiException.badRequest((teamId != null ? "팀 " : "") + provider
                    + " 키를 복호화할 수 없습니다. 키를 다시 등록해주세요");
        }
        return keyOpt.orElseThrow(() -> ApiException.badRequest(teamId != null
                ? provider + " 팀 키가 등록되어 있지 않습니다. 팀 관리에서 팀 LLM 키를 먼저 등록해주세요"
                : provider + " 키가 등록되어 있지 않습니다. 대시보드 '내 LLM 키'에서 먼저 등록해주세요"));
    }

    // 월 리셋(resetAt 도래) 지연 평가: 배치/스케줄러 없이 요청 시점에 확인 후 필요하면 즉시 리셋한다.
    // resetAt는 매달 1일에만 조건이 참이 되는 드문 이벤트라, dirty checking 대신 명시적 save()로
    // 별도 동시성 보호 없이도 실무적으로 충분하다고 판단했다 (PRD 리스크 항목의 "최소 구현").
    private UsageQuota loadQuota(Long userId) {
        UsageQuota quota = usageQuotaRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("사용량 정보를 찾을 수 없습니다"));
        if (quota.isResetDue()) {
            quota.reset();
            usageQuotaRepository.save(quota);
        }
        return quota;
    }

    // loadQuota()와 완전히 같은 지연 리셋 로직을 팀 쿼터에도 그대로 적용
    private TeamUsageQuota loadTeamQuota(Long teamId) {
        TeamUsageQuota quota = teamUsageQuotaRepository.findByTeamId(teamId)
                .orElseThrow(() -> ApiException.notFound("팀 사용량 정보를 찾을 수 없습니다"));
        if (quota.isResetDue()) {
            quota.reset();
            teamUsageQuotaRepository.save(quota);
        }
        return quota;
    }

    private JsonNode parseBody(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("요청 본문이 올바른 JSON이 아닙니다");
        }
    }

    private String extractModel(JsonNode root) {
        String model = root.path("model").asText(null);
        if (model == null) {
            throw ApiException.badRequest("model 필드는 필수입니다");
        }
        return model;
    }

    // [확인 필요] OpenAI/Anthropic API 키가 없어 실제 응답 스키마를 직접 검증하지 못했다.
    // OpenAI Chat Completions 공식 문서 기준 usage.prompt_tokens/completion_tokens를 가정했고,
    // AnthropicClient가 이미 자신의 응답을 이 형식(usage.prompt_tokens/completion_tokens)으로
    // 변환해서 돌려주므로(AnthropicClient.toOpenAiResponse) 여기서는 프로바이더별 분기 없이
    // 하나의 파서로 두 프로바이더를 모두 처리할 수 있다. 실 키 확보 후 별도 검증 필요.
    private int[] extractTokens(String responseJson) {
        try {
            JsonNode usage = objectMapper.readTree(responseJson).path("usage");
            return new int[]{usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0)};
        } catch (JsonProcessingException e) {
            log.warn("응답에서 usage를 파싱하지 못했습니다. 비용을 0으로 기록합니다");
            return new int[]{0, 0};
        }
    }

    // relay()에서 이미 단가표(LlmModel) 존재를 확인한 뒤 넘어오므로(SEC-03), 여기서는
    // Optional 처리 없이 바로 계산한다.
    private BigDecimal calculateCost(LlmModel llmModel, int inputTokens, int outputTokens) {
        return priceOf(llmModel.getInputPricePer1m(), inputTokens)
                .add(priceOf(llmModel.getOutputPricePer1m(), outputTokens));
    }

    // LlmModel의 단가는 "1M(백만) 토큰당 가격" 기준
    private BigDecimal priceOf(BigDecimal pricePer1m, int tokens) {
        return pricePer1m.multiply(BigDecimal.valueOf(tokens))
                .divide(PER_MILLION, 8, RoundingMode.HALF_UP);
    }

    private void saveLog(Long userId, Long teamId, Long apiKeyId, String model, String providerName,
                          int inputTokens, int outputTokens, BigDecimal cost,
                          boolean cacheHit, long latencyMs) {
        Team team = teamId != null ? teamRepository.getReferenceById(teamId) : null; // FK만 필요 - 불필요한 SELECT 방지
        ApiKey apiKey = apiKeyId != null ? apiKeyRepository.getReferenceById(apiKeyId) : null;
        requestLogRepository.save(RequestLog.builder()
                .user(userRepository.getReferenceById(userId)) // 팀 키여도 "발급한 사람"으로 계속 채워짐 (RequestLog 주석 참고)
                .team(team)
                .apiKey(apiKey)
                .model(model)
                .providerName(providerName)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cost(cost)
                .latencyMs(latencyMs)
                .statusCode(200) // 이 지점에 도달했다는 것 자체가 성공 응답을 의미 (실패는 예외로 먼저 빠짐)
                .cacheHit(cacheHit)
                .build());
    }
}
