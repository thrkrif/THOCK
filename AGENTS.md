# AGENTS.md

이 문서는 AI 코딩 에이전트가 이 저장소의 코드를 작업할 때 따라야 할 지침을 제공합니다.

## 프로젝트 개요

Spring Boot 3.4.1 / Java 17로 구축한 MSA 전자상거래 플랫폼("thock" — 키보드 마켓플레이스)입니다.
독립적으로 배포할 수 있는 Spring Boot 서비스 6개와 공통 라이브러리로 구성된 Gradle 멀티 모듈 모노레포이며,
Spring Cloud Gateway가 요청을 라우팅합니다. 주요 도메인 과제는 동시성 및 분산 환경에서의 주문·결제·정산 데이터
정합성입니다. 이를 위해 멱등성, 아웃박스/인박스 메시징, 내부 주문과 PG 정산 데이터 간 대사를 다룹니다.

## 모듈과 포트

| 모듈 | 포트 | 역할 |
|---|---|---|
| `api-gateway` | 8080 | Spring Cloud Gateway, 진입 지점의 JWT 검증, 라우팅(리액티브/WebFlux 스택) |
| `member-service` | 8081 | 인증(JWT/OAuth2), 회원 계정 |
| `product-service` | 8082 | 상품 카탈로그, 재고 관리 |
| `market-service` | 8083 | 주문, 장바구니, 쿠폰, 배송 — Feign으로 product-service와 payment-service 호출 |
| `payment-service` | 8084 | 지갑/결제 처리, PG 결제 승인 |
| `settlement-service` | 8085 | 일별/월별 정산 및 PG 대사 배치 |
| `common` | — | 공통 라이브러리(`java-library`, 독립 실행 불가): 보안, 예외, 아웃박스/인박스, JPA 기본 엔티티, Kafka, 로깅 |

모든 서비스는 `implementation project(':common')`으로 `common`에 의존합니다. `api-gateway`도 이에 의존하지만,
리액티브 전용이므로 서블릿/web/JPA/batch 스타터를 제외합니다.

## 빌드, 테스트, 실행

`settings.gradle`에 모듈을 등록한 단일 Gradle 루트 프로젝트(`rootProject.name = 'api'`)입니다.
모든 작업은 저장소 루트에서 `./gradlew`로 실행합니다. 모듈별 Gradle Wrapper는 없습니다.

```bash
./gradlew build                                   # 전체 모듈 빌드
./gradlew :market-service:build                   # 단일 모듈 빌드
./gradlew test                                    # 전체 모듈 테스트 실행
./gradlew :market-service:test                    # 단일 모듈 테스트 실행
./gradlew :market-service:test --tests "*.OrderCleanupSchedulerTest"   # 단일 테스트 클래스 실행
./gradlew :market-service:bootRun                 # 단일 서비스 로컬 실행
```

- CI(`.github/workflows/ci.yml`)는 MySQL 8 서비스 컨테이너를 사용해
  `./gradlew test -Dspring.profiles.active=test`를 실행한 뒤 `./gradlew build -x test`를 실행합니다.
- `settlement-service` 테스트는 별도로 재정의하지 않으면 `spring.profiles.active=local-test`를 기본값으로
  사용합니다(해당 모듈의 `build.gradle`에 설정). 다른 서비스는 명령줄로 전달한 프로필을 사용합니다(CI는 `test`).
- 각 서비스는 `application-{local,docker,test,prod}.yml` 프로필을 제공합니다. `application.yml`은 기본 포트와
  프로필 공통 설정을 정의합니다. 로컬 인프라에서 실행할 때는 `local`, `docker-compose.yml` 내부에서는 `docker`를 사용합니다.
- 전체 로컬 인프라(모든 서비스 + MySQL + Redpanda/Kafka API + Prometheus/Loki/Promtail/Grafana)는 저장소 루트의
  `docker-compose.yml`에 정의되어 있습니다. `.env` / `.env.example`에는 필요한 비밀값(JWT 키, DB 인증 정보,
  내부 서비스 인증 비밀값 등)이 있으며, `spring-dotenv`로 불러옵니다.
- `docker-compose.sonar.yml`과 루트 `build.gradle`에 SonarQube 분석(`./gradlew sonar`)이 설정되어 있으며,
  `localhost:9000`의 로컬 Sonar 서버를 대상으로 합니다.
- `k6/`와 `load-tests/`에는 k6 부하 테스트 스크립트가 있습니다(Gradle 빌드에 포함되지 않음).

## 아키텍처 패턴(모든 서비스에 일관되게 적용)

**패키지 계층.** 각 서비스는 `com.thock.back.<service>` 아래에서 동일한 in/app/out/domain 구조를 따릅니다.

- `in` — 인바운드 어댑터: REST 컨트롤러, 요청/응답 DTO(`in/dto`), 멱등성 가드(`in/idempotency`)
- `app` — 애플리케이션/유스케이스 서비스, 스케줄러(`app/scheduler`)
- `domain` — 엔티티, 값 객체, 도메인 서비스, 커맨드
- `out` — 아웃바운드 어댑터: 리포지토리, Feign 클라이언트(`out/client`), 외부 API DTO(`out/api`)

규모가 큰 서비스는 하위 도메인별로 in/app/out/domain 구조를 다시 구성합니다. 예를 들어 `market-service`에는
최상위 주문/장바구니 코드와 더불어 동일한 계층 구조의 `coupon/`, `shipping/` 하위 패키지가 있습니다.
`settlement-service`는 `settlement-service/.../settlement/` 아래에서 `reconciliation/`과 `settlement/` 하위 도메인으로 나뉩니다.

**신뢰성 있는 메시징(아웃박스/인박스).** 서비스 간 이벤트는 직접 발행하지 않고 트랜잭셔널 아웃박스를 거칩니다.
`common`의 `global.outbox` / `global.inbox` 패키지는 기본 `OutboxEvent`/`InboxEvent` 엔티티, 리포지토리,
`OutboxPoller`, `OutboxEventPublisher`, `InboxGuard`, `IdempotencyKeyResolver`를 제공합니다.
이벤트를 발행하거나 소비하는 서비스는 공통 엔티티를 직접 사용하지 않고
`<service>/messaging/{outbox,inbox,publisher}` 아래에 자체 아웃박스/인박스 구현을 둡니다(`product-service/messaging` 참고).
새 이벤트 생산자/소비자를 추가할 때도 Kafka/Redpanda로 직접 발행하지 않고 이 패턴을 따릅니다.

**멱등성.** 중복 처리를 방지해야 하는 쓰기 엔드포인트(결제, 주문)는 서비스별 `in/idempotency`의 멱등성
가드/키를 사용하며, `common`의 `IdempotencyKeyResolver`/`InboxGuard`를 기반으로 구현합니다.

**서비스 간 호출.** `market-service`는 `out/client`의 OpenFeign 클라이언트로 `product-service`와
`payment-service`를 호출합니다. Resilience4j 서킷 브레이커와 `out/client/fallback`의 폴백 팩토리로 감쌉니다.
내부 서비스 간 요청은 사용자 JWT가 아닌 `SECURITY_SERVICE_INTERNAL_SECRET`에서 가져온 값을
`X-Internal-Auth` 헤더에 담아 인증합니다(`common`의 `FeignConfig` / `InternalServiceAuthFilter` 참고).

**오류 처리.** 모든 서비스는 `common`의 `GlobalExceptionHandler` + `ErrorCode` + `ErrorResponse`를 공유합니다.
새 비즈니스 예외는 컨트롤러/서비스에서 일반 예외를 그대로 던지는 대신, `ErrorCode` 항목을 추가하고
`CustomException`을 상속해야 합니다. 공통 핸들러에는 DB/스레드 풀 고갈(`SQLTransientConnectionException`,
`CannotCreateTransactionException` 등)을 HTTP 503으로, 잠금/낙관적 충돌을 HTTP 409로 매핑하는 전용 분기가 있습니다.
새 예외 매핑은 서비스별 핸들러를 추가하지 말고 이 공통 핸들러에 추가합니다.

**보안.** JWT 검증은 `common`의 `global.security` 패키지로 공유합니다. `JwtValidator`, `SecurityConfig`,
`AuthUserArgumentResolver`와 `@AuthUser`/`AuthMember`를 사용해 인증된 사용자 정보를 컨트롤러에 주입합니다.
게이트웨이는 진입 지점에서 JWT를 검증하며, 하위 서비스는 게이트웨이를 신뢰하고 서비스 간 Feign 호출 시
내부 인증 헤더를 추가로 검증합니다.

**금액/반올림.** `settlement-service`에는 금액 처리를 위한 전용 `shared/money` 패키지가 있습니다.
정산/대사 금액을 계산할 때는 `BigDecimal`/double로 직접 연산하지 말고 이 패키지를 사용합니다.

## 커밋 및 PR 규칙

- 커밋 메시지는 `^(feat|fix|docs|style|refactor|test|chore|comment|rename|remove|hotfix): .+` 형식을 따라야 합니다.
  소문자 유형, 콜론, 공백, 비어 있지 않은 제목으로 구성하며, `.github/workflows/commit-lint.yml`에서 검사합니다.
- PR 제목은 `[TYPE] #이슈번호 요약`으로 시작해야 합니다(예: `[FEAT] #21 ...`).
  `.github/workflows/auto-label.yml`이 이를 해석해 GitHub 라벨을 자동으로 부여합니다.
- PR의 대상 브랜치는 `main` 또는 `dev`입니다. 두 브랜치 모두 CI와 커밋 메시지 검사를 실행합니다.
- `.github/workflows/deploy.yml`(CD)은 병합된 PR에서 경로가 변경된 서비스만 빌드/배포합니다.
  `common` 또는 루트 Gradle 파일이 변경되면 모든 서비스를 빌드/배포합니다. 불필요한 재배포를 피할 수 있도록
  가능한 한 관련 서비스 디렉터리로 변경 범위를 제한합니다.
