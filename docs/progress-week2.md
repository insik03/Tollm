# Tollm 2주차 진행 보고서

- **기간**: 2026-07-21
- **프로젝트**: Tollm — 팀 단위 LLM API 공유를 위한 게이트웨이 서버
- **이번 주 목표**: 2주차 필수 기능(레이트리밋 → 쿼터 차단 → 응답 캐시 → 사용량 집계 API) 전체 구현 + 자동화 테스트 인프라 최초 구축

## 1. 요약

| 구분 | 상태 |
|---|---|
| ① RateLimitService (Redis 토큰 버킷 + Lua) | ✅ 완료 + 검증 |
| ② UsageQuota (원자적 누적 + 월 리셋) | ✅ 완료 + 검증 |
| ③ ResponseCacheService (exact 캐시, userId 포함 키) | ✅ 완료 + 검증 |
| ④ ProxyService.relay() 전체 통합 | ✅ 완료 + 검증 |
| ⑤ RequestLogRepository 집계 JPQL | ✅ 완료 + 검증 |
| ⑥ UsageController / AdminController | ✅ 완료 + 검증 |
| ⑦ RequestLogAspect (@Around 관측 로깅) | ✅ 완료 |
| src/test 자동화 테스트 인프라 최초 구축 | ✅ 완료 |

**테스트: 37/37 통과** (`./gradlew clean build`, 2026-07-21 기준, 4절)

## 2. 구현한 요청 처리 흐름 (2주차 반영)

```
POST /v1/chat/completions
  → ApiKeyAuthFilter          : 1주차와 동일 (SHA-256 해시로 사용자 식별)
  → ProxyService.relay()
      1. rateLimitService.tryConsume(userId)   실패 시 429 TOO_MANY_REQUESTS
      2. usageQuota.isExceeded() (+ 지연 월 리셋) 초과 시 429 TOO_MANY_REQUESTS
      3. responseCacheService.get(cacheKey)     히트 시 즉시 반환 (cost=0, cacheHit=true 로깅)
      4. ProviderRouter → OpenAiClient/AnthropicClient (외부 호출, 1주차 기능)
      5. 응답 usage 파싱 → LlmModel 단가표로 비용 계산 → RequestLog 저장 → UsageQuota.addUsage
      6. responseCacheService.put(cacheKey, response)
  → RequestLogAspect(@Around) : 관측용 처리시간 로그(slf4j)만 남김 - RequestLog.latencyMs와는 별개(3절 참고)
```

## 3. 핵심 설계 결정과 근거

### 3.1 레이트리밋 - 토큰 버킷 파라미터/Lua 원자성

| 결정 | 근거 |
|---|---|
| Redis Hash(`bucket:{userId}` → tokens, ts) + Lua 스크립트 | GET 후 SET을 애플리케이션 코드에서 하면 동시 요청 사이 레이스 컨디션이 생긴다. Redis는 Lua 스크립트 하나를 단일 명령처럼 원자적으로 실행하므로 "경과시간만큼 리필 → capacity로 클램프 → 1개 차감"을 한 번에 안전하게 처리한다 |
| capacity=10, refillPerSec=1 (기존 application.yml 값 그대로) | PRD 지시에 따라 임의 변경하지 않음. 1초에 1개씩 채워지고 최대 10개까지 버스트를 흡수하는 완만한 정책 |
| tryConsume()은 boolean만 반환, 429 변환은 ProxyService 책임 | "토큰이 있는가"와 "그래서 어떤 HTTP 응답을 줄 것인가"는 다른 관심사 - 레이트리밋 로직을 HTTP 계층에서 분리해 재사용/테스트 용이 |
| Lua 스크립트를 `.lua` 파일로 분리 + `DefaultRedisScript` 빈 | 문자열 리터럴로 자바 코드에 박아두면 문법 하이라이팅/버전관리 diff가 어렵다. 별도 파일이면 스크립트만 따로 리뷰/테스트 가능 |

### 3.2 UsageQuota - 동시성 처리(쿼터 원자적 갱신)

| 결정 | 근거 |
|---|---|
| addUsage를 엔티티가 아니라 `UsageQuotaRepository`의 JPQL `@Modifying` bulk UPDATE로 구현 | "읽고(find) - 더하고(currentUsage+cost) - 저장(save)" 방식(dirty checking)은 동시 요청 두 개가 같은 스냅샷을 읽으면 하나의 갱신이 사라지는 lost update가 발생한다. `UPDATE ... SET currentUsage = currentUsage + :cost`는 DB가 행 잠금을 걸고 "현재 값 + cost"를 한 번의 SQL로 처리하므로 애플리케이션 레벨 락 없이 원자성이 보장된다 - Redis Lua로 레이트리밋의 원자성을 확보한 것과 동일한 철학 |
| isExceeded()는 엔티티에 남김 (조회 시점 스냅샷 판단) | "정확히 한 번만 차단"이 필요한 기능이 아니라 "월 한도를 크게 넘기지 않게 통제"가 목적이므로, 판단 시점과 실제 반영 시점 사이의 근소한 오차는 허용 가능하다고 판단 |
| 월 리셋(`resetAt` 도래 시 초기화)은 배치/스케줄러 없이 요청 시점에 지연 평가(lazy reset) | PRD가 "확인 필요"로 남긴 항목. 매달 1일 경계에서만 조건이 참이 되는 드문 이벤트라 dirty checking + 명시적 `save()`로 별도 동시성 보호 없이도 실무적으로 충분하다고 판단(스케줄러 도입은 스코프 밖) |
| `UsageQuotaRepositoryTest`에서 20개 스레드 동시 `addUsage` 테스트로 lost update 부재를 직접 증명 | "말로만 원자적"이 아니라 실제로 검증. `@Transactional(NOT_SUPPORTED)`로 @DataJpaTest의 기본 롤백 트랜잭션을 꺼서, 여러 스레드(여러 커넥션)가 준비 데이터를 실제로 커밋된 상태에서 볼 수 있게 했다(4.3절 트러블슈팅 참고) |

### 3.3 응답 캐시 - 캐시 키 설계 (보안 핵심 결정)

| 결정 | 근거 |
|---|---|
| 캐시 키 = SHA-256(userId + model + 정규화된 messages) | **[보안]** userId를 캐시 키에 포함하지 않으면, 서로 다른 사용자가 우연히 동일한 model+messages를 보냈을 때 한 사용자가 만든 캐시를 다른 사용자가 그대로 받는 크로스오버가 생긴다. 이 게이트웨이는 messages 내용을 검열/통제하지 않으므로(사용자가 프롬프트에 어떤 정보를 적을지 우리가 판단할 수 없음), 캐시 히트율이 다소 낮아지더라도 사용자 간 응답 크로스오버 가능성을 원천 차단하는 쪽을 기본값으로 선택했다. 트레이드오프: 서로 다른 사용자의 동일 질문은 캐시를 공유하지 못해 히트율이 낮아진다 - 팀(Team, [확장]) 단위 캐시 공유는 그 기능이 실제 생기면 별도로 재검토 |
| 정규화 규칙: 앞뒤 공백 제거 + 연속 공백/개행을 한 칸으로 압축 | "exact 캐시"이므로 의미적 유사도까지 매칭하는 시맨틱 캐시는 다루지 않되(non-goal), 줄바꿈/들여쓰기 같은 사소한 포맷 차이만으로 캐시가 깨지는 것은 방지 |
| 캐시 키 생성 로직(`buildKey`)의 위치: `ResponseCacheService` | "무엇을 같은 요청으로 취급할지"(정규화 규칙, 해시 알고리즘)는 캐시의 구현 세부사항이라고 판단. `ProxyService`는 모델 추출에 이미 쓴 `JsonNode`를 그대로 넘기기만 하고, 정규화 규칙이 바뀌어도 `ProxyService`를 건드릴 필요가 없다 |
| TTL 1시간 (`tollm.cache.ttl-seconds`, TollmProperties 패턴 준수) | 팀 내 반복 질의(문서 QA, 코드 리뷰 등)의 재사용 효과를 살리면서, 너무 오래된 응답이 재사용될 위험은 낮게 유지하는 절충값. 대표 확인 없이 backend-engineer 전결(PRD 리스크 표에 명시된 범위) |
| 캐시 히트/미스 카운트는 별도 Redis 카운터 없이 `RequestLog.cacheHit`로 집계 | 이미 모든 요청이 RequestLog에 남으므로, `cacheHit=true` 비율을 집계 쿼리로 구하면 되고 별도 상태를 이중 관리할 필요가 없다 |

### 3.4 ProxyService 통합 - 트랜잭션 경계

| 결정 | 근거 |
|---|---|
| `relay()` 메서드 전체에 `@Transactional`을 걸지 않음 | 4단계(외부 LLM 호출)는 최대 60초(1주차 RestClient read timeout)까지 걸릴 수 있다. 여기에 트랜잭션을 걸면 그 시간 동안 DB 커넥션 풀에서 커넥션 하나를 계속 붙들게 되어, 동시 요청이 몰릴 때 "외부 API가 느려서 DB 커넥션 풀까지 고갈"되는 상황이 생길 수 있다(1주차의 RestClient 타임아웃 결정과 같은 맥락). 그래서 쿼터 조회/리셋, 로그 저장, 쿼터 누적을 각각 리포지토리 메서드 단위의 짧은 자체 트랜잭션으로 나눴다 |
| 단가 정보가 없는 모델은 예외 대신 cost=0 + 경고 로그 | `ProviderRouter`는 모델명 접두어(`gpt-`/`claude`)만 검사하므로 단가표(`LlmModel`)에 없는 하위 모델도 라우팅은 통과할 수 있다. 이 시점엔 이미 외부 호출 비용이 발생한 뒤라, 여기서 요청을 실패시키면 정상 응답을 사용자에게 못 돌려주면서 비용만 낭비하게 된다. 그래서 응답은 정상 반환하고 비용만 0으로 기록 + 경고 로그로 관리자가 단가표를 보완하도록 유도 |
| RequestLogAspect는 `latencyMs`를 채우지 않고 관측용 로그만 남김 | 아래 3.5절 참고 |

### 3.5 AOP로 뺀 것 vs 서비스에 둔 것 (RequestLogAspect)

`RequestLogAspect`는 `ProxyService.relay()`를 `@Around`로 감싸 실행 전/후 시각을 재고 결과를 slf4j로만 남긴다. `RequestLog.latencyMs`(프로바이더 왕복 시간)는 여기서 채우지 않고 `ProxyService` 내부에서 직접 측정한다.

**이유**: `@Around`는 메서드의 "실행 전/실행 후"만 관찰할 수 있는데, `RequestLog` 저장은 `relay()` **내부**(외부 호출 직후, 메서드가 끝나기 전) 시점에 일어난다. Aspect가 잰 시간을 `RequestLog`에 반영하려면 `relay()`가 끝난 뒤 이미 저장된 로그 행을 다시 찾아 UPDATE해야 하는데, 이는 (1) 쓰기를 두 번 하는 비용과 (2) 그 사이 동시 요청이 끼어들면 엉뚱한 행을 갱신할 레이스 컨디션을 만든다. 반대로 "메서드 실행 자체를 관찰하는 로그"는 반환값/저장 시점과 무관하므로 AOP로 깔끔하게 분리할 수 있다. 이것이 AOP가 잘 맞는 "순수 횡단 관심사"(관측성/로깅)와, 메서드 내부 상태를 바로 사용해야 하는 "비즈니스 로직"(비용 계산에 쓰이는 latencyMs)의 경계라고 판단했다.

## 4. 검증 결과

### 4.1 테스트 전략 - Mock(Mockito) vs Testcontainers vs H2, 선택 근거

Redis/DB에 의존하는 로직은 세 갈래로 나눠 검증 방식을 다르게 선택했다:

| 대상 | 방식 | 이유 |
|---|---|---|
| `RateLimitService`, `ResponseCacheService`, `ProxyService`, `UsageQuota` | Mockito 단위 테스트 | 이 테스트들의 목적은 "우리 오케스트레이션/분기 로직이 맞는가"이지 "Redis/DB가 실제로 동작하는가"가 아니다. Mock으로 Redis/Repository를 대체하면 Docker 없이도 몇 초 안에 실행되고, CI 환경 차이에 흔들리지 않는다 |
| `RequestLogRepository`, `UsageQuotaRepository` (집계/bulk update JPQL) | `@DataJpaTest` + H2 임베디드 DB | 집계·누적 쿼리는 표준 JPQL(SUM/COUNT/COALESCE, bulk UPDATE)이라 MySQL 전용 문법에 기대지 않는다. Testcontainers(실제 MySQL 컨테이너)를 추가하면 빌드마다 Docker 기동 시간이 들고 신규 의존성이 늘어나는데, 이 프로젝트 규모에서 그 비용을 정당화할 만큼 MySQL 고유 동작(예: MySQL 전용 함수)에 의존하는 부분이 없다. H2로 충분하다고 판단 |
| Redis Lua 스크립트의 "실제 원자성"(진짜 동시 요청에서 레이스 컨디션이 없는지) | 코드에 남기되 **자동화 테스트 대상에서 제외** | Mock으로는 Redis 서버의 실제 동시 실행 보장을 증명할 수 없다. 이 부분은 `docker-compose up -d`로 로컬 Redis를 띄운 뒤 수동으로 대량 동시 요청을 보내 검증하는 것이 맞는데, 이번 파이프라인 환경/시간상 자동화하지 않고 리스크로 남긴다(6절 리스크 참고) |

이 조합의 핵심 장점: **`./gradlew build`가 Docker 없이도 항상 통과한다** (PRD 필수 조건). Testcontainers를 선택하지 않은 이유는 위 표와 같다.

### 4.2 테스트 결과 (37/37 통과, `./gradlew clean build`)

| 영역 | 파일 | 케이스 수 | 비고 |
|---|---|---|---|
| RedisConfig | `RedisConfigTest` | 1 | Lua 스크립트 리소스 로드 확인 |
| RateLimitService | `RateLimitServiceTest` | 4 | 허용/거부/null 안전 처리/버킷 키 조립 |
| ResponseCacheService | `ResponseCacheServiceTest` | 7 | 키 결정성, **사용자별 키 분리(보안)**, 정규화, get/put TTL |
| UsageQuota (엔티티) | `UsageQuotaTest` | 5 | 초과 판정, 리셋 판정/실행, 한도 변경 |
| UsageQuotaRepository | `UsageQuotaRepositoryTest` | 2 | **동시 20건 addUsage → lost update 없음**, 사용자 간 격리 |
| RequestLogRepository | `RequestLogRepositoryTest` | 6 | 0건 집계, 정확한 합산, 기간 필터, 사용자 격리, 전체 집계, 페이징 정렬 |
| ProxyService (통합) | `ProxyServiceTest` | 7 | 429(레이트리밋/쿼터), 404(쿼터 없음), 캐시 히트, 캐시 미스+비용계산, 단가 없음, 400(model 누락) |
| UsageController | `UsageControllerTest` | 2 | DTO 응답, **엔티티 미노출 확인** |
| AdminController | `AdminControllerTest` | 3 | 집계 응답, 204, 검증 실패 400 |
| **합계** | | **37** | **37 통과 / 0 실패** |

`./gradlew clean build` 결과 요약:
```
> Task :compileJava
> Task :bootJar
> Task :test
> Task :check
> Task :build
BUILD SUCCESSFUL in 17s
8 actionable tasks: 8 executed
```

### 4.3 트러블슈팅

1. **`./gradlew test`가 새로 추가한 모든 테스트 클래스에서 `ClassNotFoundException`으로 전멸** — 원인 추적에 가장 많은 시간을 썼다. 이 프로젝트 경로(`OneDrive\바탕 화면\tollm`, 한글+공백)에서 Gradle이 테스트 워커를 포크할 때, 의존성 개수가 많아(Spring Boot ~90개 jar) 클래스패스를 임시 `@argfile`(`gradle-worker-classpathNNN.txt`)로 넘긴다. 그런데 이 파일을 읽는 JDK 네이티브 런처가 OS 기본 코드페이지(한국어 Windows는 MS949)로 디코딩하는데, Gradle이 UTF-8로 쓴 파일이라 인코딩이 어긋난다 - 이 디코딩은 JVM이 뜨기도 전(네이티브 런처 단계)에 일어나서 `-Dfile.encoding`/`-Dsun.jnu.encoding` 같은 JVM 옵션으로는 손 쓸 수 없다. 그 결과 `build/classes/...` 경로 중 "바탕 화면" 세그먼트가 깨져서 그 아래 있는 우리 테스트 클래스만 못 찾고(라이브러리 jar는 전부 ASCII 경로라 멀쩡했다), 직접 `java -cp "build/classes/java/test" ...`로 실행하면 문제없이 로드되는 것으로 원인을 확정했다. **해결**: 소스 코드 위치는 그대로 두고(이동/복사 금지 요구사항 준수), `build.gradle`에서 Windows일 때만 `layout.buildDirectory`를 시스템 임시 디렉터리(`java.io.tmpdir`, 항상 ASCII) 하위로 리다이렉트해 빌드 "산출물" 위치만 문제 경로를 피하게 했다.
2. **`@WebMvcTest`에 커스텀 Filter가 딸려 올라옴** — `UsageControllerTest`/`AdminControllerTest`가 `NoSuchBeanDefinitionException(ApiKeyRepository)`로 컨텍스트 로딩에 실패했다. `@WebMvcTest`는 컨트롤러만 슬라이스로 띄운다고 생각했는데, `@Component`로 등록된 `OncePerRequestFilter`(`JwtAuthFilter`, `ApiKeyAuthFilter`)는 이 슬라이스에도 함께 올라온다는 것을 확인했다. `@MockBean`으로 필터의 의존성(`JwtProvider`, `ApiKeyRepository`)을 채워 컨텍스트는 띄우되, `@AutoConfigureMockMvc(addFilters = false)`로 필터의 실제 실행은 꺼서(필터 인증 로직 자체는 이 테스트의 관심사가 아님) `requestAttr()`로 시뮬레이션한 인증 상태가 그대로 컨트롤러에 전달되게 했다.
3. **`@DataJpaTest`의 기본 트랜잭션 롤백과 멀티스레드 테스트의 충돌** — `UsageQuotaRepositoryTest`에서 여러 스레드로 동시에 `addUsage`를 호출하는 테스트를 짜다가, 준비 데이터(User/UsageQuota)가 메인 스레드의 트랜잭션 안에 있어 아직 커밋되지 않은 상태라 다른 스레드(다른 커넥션)에서는 그 행이 안 보이는(격리 수준상 dirty read 불가) 문제를 겪었다. `@Transactional(propagation = Propagation.NOT_SUPPORTED)`로 `@DataJpaTest`의 기본 트랜잭션 래핑을 꺼서 각 단계가 즉시 커밋되게 해결했다.

## 5. 학습 기록 — 구현하며 스스로 묻고 답한 질문들 (1주차에서 이어지는 질문)

**Q. Redis 레이트 리밋에서 GET 후 SET 방식은 왜 레이스 컨디션이 생기고, Lua 스크립트는 이를 어떻게 해결하나?**
GET과 SET을 애플리케이션 코드에서 따로 실행하면 그 사이에 다른 스레드/서버가 끼어들어 같은 "남은 토큰 수"를 읽고, 둘 다 "1개 차감한 값"을 저장해버릴 수 있다(둘 다 9를 읽고 둘 다 8을 쓰면, 실제로는 토큰이 2개 소비됐는데 1개만 소비된 것처럼 기록된다). Redis의 Lua 스크립트는 실행 도중 다른 클라이언트의 명령이 절대 끼어들 수 없는 단일 명령처럼 동작하므로, "리필 계산 → 클램프 → 차감 → 저장"을 하나의 원자적 단위로 만들 수 있다.

**Q. 고정 윈도우 카운터의 경계 문제는 무엇이고, 토큰 버킷은 이를 어떻게 피하나?**
고정 윈도우는 "0~59초" 같은 창구가 리셋되는 순간을 기준으로 카운트하는데, 창구 경계 직전(59초)과 직후(다음 0초)에 각각 한도만큼 요청을 보내면 실제로는 짧은 시간에 2배의 요청이 통과한다. 토큰 버킷은 "리셋 시점"이라는 개념 자체가 없고 "초당 일정량씩 꾸준히 채워지는 그릇"으로 모델링하므로 이런 경계 폭주가 구조적으로 생기지 않는다.

**Q. 쿼터 누적을 "읽고-더하고-쓰기"로 하면 왜 갱신 유실(lost update)이 생기나?**
두 요청이 동시에 같은 사용자의 쿼터를 갱신한다고 하자. 둘 다 `currentUsage=$3`을 읽고, 각각 `+$1`, `+$2`를 계산해 `$4`, `$5`로 저장한다. 나중에 저장한 값이 먼저 저장한 값을 덮어써서 최종 결과는 `$3+$2=$5`가 아니라 둘 중 하나만 반영된 값이 남는다(실제로는 `$3+$1+$2=$6`이어야 함). `UPDATE ... SET x = x + :cost`처럼 "현재 값에 상대적으로" 갱신하는 SQL은 DB가 행 잠금을 잡고 한 번에 처리하므로 이 문제가 생기지 않는다.

**아직 답을 만드는 중인 질문 (3주차 학습 예정)**
- Lua 스크립트의 원자성을 실제 동시 요청(멀티 프로세스/멀티 스레드)으로 부하 테스트하면 정말 레이스 컨디션이 없는지 (k6 등으로 3주차에 검증 예정)
- 캐시 히트율을 실제로 얼마나 끌어올릴 수 있는지 (임베딩 기반 시맨틱 캐시 도입 시 정확도-비용 트레이드오프는 어떻게 되는지)

## 6. 다음 계획 / 리스크

1. qa-reviewer / security-engineer 검수 (특히 레이트리밋/쿼터 동시성, 캐시 키 사용자 분리, `/admin/**` 권한 우회 여부)
2. **[확인 필요]** 프로바이더 응답의 `usage.prompt_tokens`/`completion_tokens` 필드 위치는 API 키가 없어 실물로 검증하지 못했다 - 공식 문서 기준 스키마를 가정하고 mock 응답으로 테스트를 고정했다. 실 키 확보 후 검증 필요
3. **[리스크]** Redis Lua 스크립트의 실제 원자성은 mock 테스트로 증명되지 않는다 - `docker-compose up -d` 후 동시 다발 요청으로 수동 검증 필요 (자동화는 3주차 k6 부하 테스트 때 함께 고려)
4. **[리스크]** `LlmModel` 단가 데이터가 비어 있으면(현재 시드 데이터 없음) 모든 요청의 비용이 0으로 기록된다 - 실사용/데모 전 관리자가 단가 데이터를 수동 등록해야 한다(`INSERT`는 `01-backend-design.md` 참고). 시드 데이터(data.sql) 추가는 이번 PRD 스코프에 없어 손대지 않았다
5. 3주차: EC2+Nginx 배포, k6 부하 테스트, 인덱스/캐시 개선 수치 기록
