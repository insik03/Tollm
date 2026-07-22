package com.tollm.domain.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tollm.domain.provider.LlmModel;
import com.tollm.domain.provider.LlmModelRepository;
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
import com.tollm.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

    // 팀 키(add-on) 지원 버전. teamId == null이면 아래 모든 분기가 기존 개인 키 동작과 100% 동일하다 -
    // "재설계"가 아니라 팀 키일 때만 타는 병렬 경로를 얹은 것 (README/보고서 설계 근거 참고)
    public String relay(Long userId, Long teamId, String body) {
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

        // 팀원끼리는 캐시를 공유한다(팀 단위 buildKeyForTeam) - 개인 키는 기존과 동일하게 본인만 히트
        String cacheKey = isTeam ? responseCacheService.buildKeyForTeam(teamId, root) : responseCacheService.buildKey(userId, root);
        String cached = responseCacheService.get(cacheKey);
        if (cached != null) {
            saveLog(userId, teamId, model, client.providerName(), 0, 0, BigDecimal.ZERO, true, 0L);
            return cached;
        }

        long start = System.currentTimeMillis();
        String response = client.chat(body);
        long latencyMs = System.currentTimeMillis() - start;

        int[] tokens = extractTokens(response);
        int inputTokens = tokens[0];
        int outputTokens = tokens[1];
        BigDecimal cost = calculateCost(llmModel, inputTokens, outputTokens);

        saveLog(userId, teamId, model, client.providerName(), inputTokens, outputTokens, cost, false, latencyMs);
        if (isTeam) {
            teamUsageQuotaRepository.addUsage(teamId, cost);
        } else {
            usageQuotaRepository.addUsage(userId, cost);
        }

        responseCacheService.put(cacheKey, response);
        return response;
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

    private void saveLog(Long userId, Long teamId, String model, String providerName,
                          int inputTokens, int outputTokens, BigDecimal cost,
                          boolean cacheHit, long latencyMs) {
        Team team = teamId != null ? teamRepository.getReferenceById(teamId) : null; // FK만 필요 - 불필요한 SELECT 방지
        requestLogRepository.save(RequestLog.builder()
                .user(userRepository.getReferenceById(userId)) // 팀 키여도 "발급한 사람"으로 계속 채워짐 (RequestLog 주석 참고)
                .team(team)
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
