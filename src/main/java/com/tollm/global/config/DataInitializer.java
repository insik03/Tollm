package com.tollm.global.config;

import com.tollm.domain.provider.LlmModel;
import com.tollm.domain.provider.LlmModelRepository;
import com.tollm.domain.provider.Provider;
import com.tollm.domain.provider.ProviderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

// 부팅 시 Provider/LlmModel 단가표를 시드한다. 이게 없으면 ProxyService가 모든 요청을
// "단가 정보 없음"(SEC-03, 400)으로 거부해 데모 자체가 불가능하다 - week2 보고서의 리스크 항목이었던
// "데모 전 관리자가 단가 데이터를 수동 등록해야 한다"를 해소한다.
// findByName으로 존재 여부를 먼저 확인해 재부팅 시 중복 삽입을 방지한다 (idempotent).
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final ProviderRepository providerRepository;
    private final LlmModelRepository llmModelRepository;

    @Override
    @Transactional
    public void run(String... args) {
        Provider openai = providerRepository.findByName("openai")
                .orElseGet(() -> providerRepository.save(
                        Provider.builder().name("openai").baseUrl("https://api.openai.com").build()));
        Provider anthropic = providerRepository.findByName("anthropic")
                .orElseGet(() -> providerRepository.save(
                        Provider.builder().name("anthropic").baseUrl("https://api.anthropic.com").build()));

        // 데모/테스트 비용이 크게 나오지 않도록 각 프로바이더의 저가 모델만 시드한다.
        // 단가는 공급사 공개 가격 기준(1M 토큰당 USD) - 실 키 확보 후 변동 시 갱신 필요.
        seedIfAbsent(openai, "gpt-4o-mini", "0.15", "0.60");
        seedIfAbsent(anthropic, "claude-haiku-4-5", "1.00", "5.00");
    }

    private void seedIfAbsent(Provider provider, String modelName, String inputPer1m, String outputPer1m) {
        if (llmModelRepository.findByName(modelName).isPresent()) {
            return;
        }
        llmModelRepository.save(LlmModel.builder()
                .provider(provider)
                .name(modelName)
                .inputPricePer1m(new BigDecimal(inputPer1m))
                .outputPricePer1m(new BigDecimal(outputPer1m))
                .build());
    }
}
