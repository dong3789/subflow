# SubFlow

**구독·정기결제를 붙이는 가장 짧은 길 — 오픈소스 Plug-and-Play 구독 SaaS.**

기존 서비스에 **API Key + Hosted Checkout + Outbox Webhook** 세 가지만 연결하면,
빌링키 발급부터 정기 청구·Dunning(연체 재시도)·해지·정합성 점검까지 바로 쓸 수 있게 만드는 것이 목표다.
1단계(이번 단계)는 그 위에 얹을 **고신뢰 결제 백엔드의 뼈대**다.

---

## 왜 만드는가

1. **회사에서 부족했던 구독 기능을 직접 보강한다.**
   정기배송·구독 서비스를 운영하면서 "있으면 좋겠다"가 아니라 "없어서 사고가 난다"고 느낀 것들 —
   중복 결제, 연체 처리 누락, 청구일 누락, 해지했는데 계속 결제되는 일 — 을 도메인 규칙과 DB 제약으로 못 박는다.
2. **Node.js가 아닌 기술로 고신뢰 구독 시스템을 검증한다.**
   Java 21 + Spring Boot 3.4 기반에서 트랜잭션 경계, 낙관적 락, 분산 락, Outbox 패턴이
   실제로 어떤 코드 모양이 되는지, 어디서 실패하고 어떻게 복구하는지를 직접 확인한다.
3. **다른 개발자가 내 서비스에 붙일 수 있는 오픈소스 구독 SaaS로 만든다.**
   특정 회사 내부 도구가 아니라, 멀티 프로젝트(Multi-Project)를 전제로 한 제품이 되도록 설계한다.
   각 프로젝트는 자기 API Key로 격리되고, 자기 도메인에서 Hosted Checkout으로 결제를 받고,
   자기 서버로 Webhook을 받는다.

> **1단계 범위**: 도메인 모델 + 상태 머신 + PostgreSQL 스키마(Flyway) + 빌드/테스트 기반.
> `projects`/API Key 인증, Hosted Checkout, Webhook 발신, 실제 PG 어댑터는 **2단계**에서 얹는다
> (아래 [로드맵](#로드맵)).

---

## 기술 스택

| 구분 | 선택 | 이유 |
| --- | --- | --- |
| 언어/런타임 | Java 21 | Records, 패턴 매칭, 가상 스레드로 도메인 코드를 짧고 안전하게 |
| 프레임워크 | Spring Boot 3.4.7 | 트랜잭션·JPA·Actuator·검증의 표준 조합 |
| 빌드 | Gradle 8.14.3 (Kotlin DSL) + Wrapper | 재현 가능한 빌드, JDK 21 toolchain 고정 |
| DB | PostgreSQL 16 | `JSONB`, 부분 유니크 인덱스, `timestamptz` — 결제 데이터에 필요한 제약을 그대로 쓴다 |
| 마이그레이션 | Flyway (`V1__init.sql`) | 스키마의 단일 출처. JPA는 `ddl-auto: validate`로만 검증 |
| 캐시/락 | Redis 7 + Redisson | 2단계 정기결제 실행의 구독 단위 분산 락 |
| 관측 | Spring Boot Actuator | 헬스·메트릭. 2단계에서 Prometheus/Grafana 연결 |
| 테스트 | JUnit 5 + AssertJ + H2 | 도메인 단위 테스트는 DB 없이, 매핑/쿼리는 H2로 빠르게 |

---

## 아키텍처 핵심 포인트

### 1) 구독 상태 머신 — 상태는 서비스가 아니라 도메인이 지킨다

```
TRIAL ──▶ ACTIVE ──▶ PAST_DUE ──▶ ACTIVE   (Dunning 성공)
  │         │            │
  │         │            └──▶ EXPIRED       (재시도 한도 초과)
  │         ├──▶ PAUSED ──▶ ACTIVE
  │         └──▶ CANCELED ──▶ EXPIRED       (기간 종료)
  └──▶ CANCELED / EXPIRED
```

- `SubscriptionStatus`가 전이 매트릭스(`canTransitionTo`)의 **단일 출처**다.
- 상태 변경은 `Subscription`의 도메인 메서드(`start`, `renew`, `registerPaymentFailure`,
  `cancelAtPeriodEnd`, `applyPendingCancellation`, `pause`, `resume`, `expire`)로만 한다.
  엔티티에 setter를 열지 않았기 때문에 서비스가 상태를 임의로 덮어쓸 수 없다.
- 잘못된 전이는 `InvalidStateTransitionException` → HTTP 409.
- 배치가 두 번 돌아도 깨지지 않도록 `expire()`, `cancelNow()`는 멱등이다.

### 2) 멱등성 2중 방어 — 멱등키 + 분산 락 (2단계에서 락 사용)

| 방어선 | 위치 | 막는 것 |
| --- | --- | --- |
| `payments.idempotency_key` UNIQUE | DB | 같은 승인 요청이 두 번 결제되는 것 |
| `webhook_inbox(pg_provider, event_id)` UNIQUE | DB | PG가 같은 웹훅을 재전송하는 것 |
| Redisson 분산 락 `subflow:lock:subscription:{id}` | Redis (2단계) | 여러 인스턴스의 배치가 같은 구독을 동시에 청구하는 것 |
| `@Version` 낙관적 락 | JPA | 같은 구독에 대한 동시 상태 변경 유실 |

락은 "성능 장치"가 아니라 **마지막 방어선의 앞단**이다. 락이 만료돼도 DB 제약이 최종적으로 중복을 막는다.

### 3) Transactional Outbox — 커밋과 이벤트 발행의 원자성

결제 성공/실패, 구독 만료 같은 사건은 **도메인 변경과 같은 트랜잭션**으로 `outbox_events`에 적재한다.
발행기(2단계)가 `status = PENDING and next_retry_at <= now`를 `id` 순으로 가져가 `PUBLISHED`로 표시한다.

- 커밋은 됐는데 이벤트가 유실되는 문제, 이벤트는 나갔는데 롤백되는 문제를 둘 다 제거한다.
- 실패는 `retry_count`/`next_retry_at`/`last_error`로 남고, 한도 초과 시 `FAILED`로 확정해 수동 개입 대상이 된다.
- 수신 측(PG 웹훅)은 `webhook_inbox`에 원본을 먼저 저장하고 처리하므로, 재전송에도 안전하다.

### 4) 정합성 점검 — "결제는 됐는데 구독이 안 열렸다"를 매일 찾는다

`reconciliation_logs`에 일일 점검 결과(점검 건수·불일치·자동 보정)를 남긴다. 2단계 점검 항목:

- PG 정산 내역 ↔ `payments` 성공 건수/금액 대사
- `next_billing_at`이 지났는데 청구되지 않은 구독
- 성공 결제가 있는데 `ACTIVE`로 돌아오지 않은 구독
- 오래 `PENDING`에 머문 결제, `next_retry_at`이 지난 Outbox 이벤트

---

## 데이터 모델 (V1)

| 테이블 | 역할 | 핵심 제약 |
| --- | --- | --- |
| `plans` | 상품: 가격·주기·체험일 | `code` UNIQUE, `amount >= 0` |
| `customers` | 고객(테넌트) | `email` UNIQUE, `external_key` UNIQUE |
| `billing_keys` | PG 빌링키(카드 원본 미보관) | 고객당 기본키 1개(부분 유니크 인덱스) |
| `subscriptions` | 구독 애그리거트 | 상태 CHECK, `period_end > period_start`, `version` |
| `payments` | 결제 원장(시도 1건 = 1행) | `idempotency_key` UNIQUE |
| `payment_attempts` | 승인 시도/Dunning 이력 | `(payment_id, attempt_no)` UNIQUE |
| `webhook_inbox` | 수신 웹훅 보관함 | `(pg_provider, event_id)` UNIQUE |
| `outbox_events` | 발행 대기 이벤트 | 상태 CHECK, 부분 인덱스(`PENDING`) |
| `reconciliation_logs` | 정합성 점검 결과 | 건수 CHECK |

설계 메모:

- **카드 정보는 저장하지 않는다.** PG가 준 빌링키 토큰과 표시용 마지막 4자리만 보관(PCI 범위 최소화).
- **금액은 `numeric(12,2)`**, 통화는 ISO 4217 3자리. 결제 원장이 금액을 보관하므로 플랜 가격이 바뀌어도 과거 결제는 불변.
- **시각은 전부 `timestamptz`(UTC)**. 표시 시간대 변환은 API 계층에서 한다.
- **상태는 varchar + CHECK**. PostgreSQL enum 타입은 값 추가·삭제 마이그레이션이 번거로워 쓰지 않았다.

---

## 디렉토리 구조

```
subflow/
├── README.md
├── docker-compose.yml            # PostgreSQL 16 + Redis 7 (healthcheck 포함)
└── backend/
    ├── build.gradle.kts          # Spring Boot 3.4.7, Java 21 toolchain
    ├── gradlew / gradle/         # Gradle Wrapper 8.14.3
    └── src/
        ├── main/java/com/subflow/
        │   ├── SubFlowApplication.java
        │   ├── api/                  # HealthController, dto
        │   ├── common/error/         # BusinessException, GlobalExceptionHandler, ErrorResponse
        │   ├── config/               # SubFlowProperties (dunning/outbox/lock/webhook)
        │   └── domain/
        │       ├── plan/             # Plan, BillingCycle
        │       ├── customer/         # Customer
        │       ├── billingkey/       # BillingKey
        │       ├── subscription/     # Subscription, SubscriptionStatus(상태 머신)
        │       ├── payment/          # Payment, PaymentAttempt
        │       ├── outbox/           # OutboxEvent
        │       ├── webhook/          # WebhookInbox
        │       ├── reconciliation/   # ReconciliationLog
        │       └── support/          # BaseTimeEntity
        ├── main/resources/
        │   ├── application.yml
        │   ├── application-test.yml  # H2, Flyway off, ddl-auto create-drop
        │   └── db/migration/V1__init.sql
        └── test/java/com/subflow/
            ├── domain/…              # 상태 머신·결제·Outbox·주기 단위 테스트
            ├── persistence/          # 매핑/쿼리 테스트, 스키마-엔티티 일치 검사
            └── api/                  # 헬스·예외 응답 테스트
```

---

## 로컬 실행

### 1. 인프라 (PostgreSQL + Redis)

```bash
docker compose up -d
docker compose ps          # 둘 다 healthy 인지 확인
```

| 컨테이너 | 포트 | 접속 정보 |
| --- | --- | --- |
| `subflow-postgres` | 5432 | DB `subflow` / user `subflow` / pw `subflow` |
| `subflow-redis` | 6379 | 비밀번호 없음(로컬 전용) |

### 2. 백엔드

```bash
cd backend
./gradlew bootRun
```

기동 시 Flyway가 `V1__init.sql`을 적용하고, Hibernate는 `ddl-auto: validate`로 엔티티와 스키마가
일치하는지 검사한다(불일치 시 기동 실패 — 스키마 드리프트를 조기에 잡는 장치).

```bash
curl http://localhost:8080/api/v1/health
# {"status":"UP","service":"subflow-backend","version":"0.1.0-SNAPSHOT","timestamp":"…"}

curl http://localhost:8080/actuator/health
```

### 3. 테스트

```bash
cd backend
./gradlew test
```

---

## 설정

`application.yml`의 `subflow.*` 값은 2단계에서 결제 실행·발행·점검이 그대로 주입받아 쓴다.

| 키 | 기본값 | 의미 |
| --- | --- | --- |
| `subflow.dunning.max-retries` | 3 | 결제 실패 후 재시도 최대 횟수(도달 시 구독 만료) |
| `subflow.dunning.retry-interval` | 24h | 재시도 간격 |
| `subflow.outbox.batch-size` | 100 | 발행 배치 크기 |
| `subflow.outbox.max-retries` | 5 | 이벤트 발행 최대 재시도 |
| `subflow.outbox.retry-backoff` | 30s | 발행 재시도 백오프 |
| `subflow.lock.key-prefix` | `subflow:lock:` | 분산 락 키 접두사 |
| `subflow.lock.lease-time` / `wait-time` | 5s / 3s | 락 임대·획득 대기 |
| `subflow.webhook.timestamp-tolerance` | 5m | 웹훅 서명 시각 허용 오차 |

환경 변수로 덮어쓸 수 있다: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`,
`REDIS_HOST`, `REDIS_PORT`, `SERVER_PORT`.

---

## 테스트 현황 (1단계)

`./gradlew test` — **47개 테스트 통과** (Java 21 / Gradle 8.14.3).

| 테스트 | 검증 내용 |
| --- | --- |
| `SubscriptionStateMachineTest` (22) | 시작 상태·청구일, 전이 매트릭스 전체 조합, Dunning 누적→만료, 연체 해소, 기간 종료 해지, 즉시 해지 멱등성, 일시정지/재개, 체험→유료 전환 |
| `PersistenceMappingTest` (8) | 엔티티 매핑, 청구 대상/해지 대상 배치 쿼리, 멱등키·웹훅 유니크 제약, 결제·시도 이력, Outbox JSON, 점검 로그 |
| `SchemaParityTest` (1) | `V1__init.sql`의 테이블·컬럼과 JPA 매핑의 **일치 검사**(스키마 드리프트 차단) |
| `HealthControllerTest` (4) | 헬스 응답, 404/409/500 예외 매핑과 내부 메시지 비노출 |
| `PaymentTest` (6), `OutboxEventTest` (3), `BillingCycleTest` (3) | 결제 상태 전이, Outbox 재시도, 주기 계산(말일·윤년 보정) |

**검증 범위에 대한 정직한 메모**: 이 환경에는 Docker가 없어 실제 PostgreSQL에 Flyway를 적용하는
기동 검증은 하지 못했다. 대신 ①스키마 SQL과 엔티티 매핑의 테이블·컬럼 일치 검사, ②H2(PostgreSQL 호환 모드)에서
매핑·JPQL 동작 검증을 수행했다. `docker compose up -d && ./gradlew bootRun`으로 실제 기동 확인을 권한다.

---

## API (1단계)

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/v1/health` | 앱 생존 확인 (`status`, `service`, `version`, `timestamp`) |
| GET | `/actuator/health` | DB·Redis 포함 인프라 헬스 |

오류 응답은 모두 같은 형태다.

```json
{
  "timestamp": "2026-11-25T06:00:00Z",
  "status": 409,
  "code": "INVALID_STATE_TRANSITION",
  "message": "구독 상태를 EXPIRED에서 ACTIVE(으)로 바꿀 수 없습니다.",
  "path": "/api/v1/subscriptions/42"
}
```

---

## 로드맵

### 2단계 — 결제 실행 엔진 (다음 단계)

- **빌링키·정기결제 실행 서비스**: PG 어댑터 인터페이스 + Mock PG 시뮬레이터(승인/한도초과/카드정지/타임아웃)
- **Redisson 분산 락 + 멱등키**: 구독 단위 직렬화, 락 획득 실패 시 재시도 정책
- **Dunning 스케줄러**: 실패 → `PAST_DUE` → 재시도 → 한도 초과 `EXPIRED`, `payment_attempts` 기록
- **Outbox 발행기**: PENDING 배치 발행, 실패 백오프, `FAILED` 격리
- **웹훅 수신 API**: 서명 검증 → `webhook_inbox` 적재 → 멱등 처리 → 200

### 3단계 — 멀티 프로젝트 SaaS (Plug-and-Play)

- **`projects` 테이블 + API Key 인증**: 프로젝트별 `pk_live_…`/`pk_test_…` 키(해시 저장), 요청 스코프 격리
- **Hosted Checkout**: 프로젝트 서버가 링크를 만들면 SubFlow가 결제 페이지를 호스팅(카드 등록·빌링키 발급)
- **Outbox Webhook**: 프로젝트가 등록한 엔드포인트로 서명된 웹훅 발신(HMAC + 재시도 + `event_id` 멱등)
- **정합성 점검 배치**: 일일 대사, 불일치 대시보드, 자동 보정
- **Quota/요금제**: 프로젝트별 월 결제 건수 제한, 사용량 리포트

### 4단계 — 운영

- OpenTelemetry 트레이싱, Prometheus/Grafana 대시보드
- 감사 로그(누가 무엇을 바꿨는지), 개인정보 파기 정책
- 실제 PG 연동(토스페이먼츠/포트원), 카드 교체·환불·부분취소

---

## 라이선스

오픈소스로 공개할 예정이며, 라이선스는 저장소 공개 시점에 확정한다(현재 미정).
