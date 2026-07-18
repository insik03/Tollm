# Tollm — 팀을 위한 AI(LLM) API 게이트웨이

> Toll(통행료) + LLM. 모든 AI 요청이 통과하며 인증·과금·제한이 이루어지는 관문

## 요청 처리 흐름

클라이언트 → [1.인증 필터] → [2.레이트 리밋·쿼터] → [3.캐시 확인] → [4.프로바이더 라우팅] → 외부 LLM API → [5.로깅·비용 집계] → 응답

## 패키지 구조

```
com.tollm
├── domain
│   ├── user      # 회원, 인증 (JWT)
│   ├── team      # 팀, 팀원 (확장)
│   ├── apikey    # 게이트웨이 키 발급/관리
│   ├── provider  # LLM 제공사, 모델 단가
│   ├── proxy     # 프록시 핵심: 레이트리밋, 캐시, 라우팅
│   ├── usage     # 요청 로그, 쿼터, 사용량 조회
│   └── admin     # 관리자 통계
└── global
    ├── auth      # JwtProvider, 인증 필터 2종
    ├── config    # Redis, RestClient 설정
    ├── logging   # AOP 요청 로깅
    └── error     # 공통 예외 처리
```

## 구현 순서 (TODO 태그 기준)

1. **[1주차 후반~2주차 초]** AuthController/Service → JwtProvider → ApiKey 발급 → ApiKeyAuthFilter → ProxyController(RestClient 호출) → RequestLog 저장
2. **[2주차]** RateLimitService(Redis 토큰 버킷+Lua) → UsageQuota 차단 → ResponseCacheService → 집계 API
3. **[3주차]** EC2+Nginx 배포 → k6 부하 테스트 → 인덱스/캐시 개선 수치 기록

## 로컬 실행 준비

- MySQL 8: `tollm` 스키마 생성
- Redis: `docker run -p 6379:6379 redis` 또는 로컬 설치
- 환경변수: `OPENAI_API_KEY` 또는 `ANTHROPIC_API_KEY`, `JWT_SECRET`

## 기술 선정 근거 (발표/면접 대비 - 채워나갈 것)

- 왜 Java 21? → 로컬에 JDK 21이 이미 설치돼 있고, Spring Boot 3.3이 공식 지원하는 최신 LTS. JDK 17을 추가 설치해 두 버전을 관리할 이유가 없음
- 왜 Redis로 레이트 리밋? →
- 왜 토큰 버킷? (고정 윈도우의 문제점) →
- 왜 키를 해시로 저장? → DB가 유출돼도 키 원문이 없다. 발급 응답에서 딱 1회만 원문 노출
- 왜 비밀번호는 bcrypt인데 API 키는 SHA-256? → 해싱은 입력 엔트로피와 검증 빈도로 결정. 비밀번호는 저엔트로피(사람이 만듦)·저빈도라 느린 bcrypt로 무차별 대입 방어, API 키는 256비트 랜덤(추측 불가)·매 요청 검증이라 빠른 SHA-256 + 결정적 해시 덕분에 유니크 인덱스 단건 조회 가능
- 왜 OpenAI 호환 API 형식? → 기존 도구/SDK가 base URL만 바꿔 그대로 사용 가능. 프로바이더별 형식 차이는 어댑터(AnthropicClient)가 내부에서 흡수
- 왜 RestClient 타임아웃 필수? → 외부 LLM API가 응답을 안 주면 톰캣 스레드가 하나씩 물려 스레드 풀 고갈 → 서버 전체 마비. connect 3s / read 60s로 차단
