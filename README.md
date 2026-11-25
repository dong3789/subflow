# SubFlow

**구독·정기결제를 붙이는 가장 짧은 길 — 오픈소스 Plug-and-Play 구독 SaaS.**

기존 서비스에 **API Key + Hosted Checkout + Outbox Webhook** 세 가지만 연결하면
빌링키 발급부터 정기 청구·Dunning(연체 재시도)·해지·정합성 점검까지 바로 쓸 수 있게 만드는 것이 목표다.
이 저장소는 그 중 **결제 코어 엔진(2단계)** 까지 구현되어 있다.

---

## 무엇이 되어 있나 (2단계 완료)

| 영역 | 구현 |
| --- | --- |
| **정기결제 실행** | 만기 구독 청구 배치, 당겨받기(즉시 갱신), 결제 성공 시 회차·다음 청구일·Outbox 원자 반영 |
| **중복 결제 3중 방어** | 구독 단위 분산 락(Redisson/인메모리 전환 가능) → DB 비관적 락 → `payments.idempotency_key` 유니크 |
| **중복 결제 가드** | `last_billed_at` 기준 당일 차단 + 보호 윈도우(기본 24h). 당겨받기·배치·주기 변경 직후 재청구를 모두 막는다 |
| **Dunning** | 실패 → `PAST_DUE` → 재시도 → 한도 초과 `EXPIRED`. **미결제 상태에서 해지되면 즉시 재시도 대상에서 제외** |
| **PG 타임아웃** | 실패로 단정하지 않고 `PENDING`(미확정)으로 남겨 정합성 점검이 확정. 재청구하지 않는다 |
| **주기 변경** | `WEEKLY`·`BIWEEKLY`·`FOUR_WEEKLY`·`MONTHLY`·`YEARLY` 지원, 기준일(anchor day) 명시적 재계산 + 변경 미리보기 |
| **플랜/수량 변경** | 구독을 해지·재생성하지 않고 같은 구독에서 회차 유지, before/after 스냅샷 이력 |
| **해지/복구** | 사유 포함 해지, 기간 종료 해지 vs 즉시 해지, 관리자 복구, 감사 로그 |
| **단종 플랜** | 신규 구독·플랜 변경 차단, 기존 구독의 정기결제는 유지 |
| **Mock PG** | 승인·잔액 부족·한도 초과·카드 정지·타임아웃·멱등 재응답 시뮬레이터 (`/api/v1/mock-pg`) |
| **Outbox → 웹훅** | 프로젝트 `webhook_url` 로 HMAC-SHA256 서명(`X-SubFlow-Signature`) POST, 실패 시 지수 백오프, 한도 초과 `FAILED` |
| **웹훅 수신** | `webhook_inbox(pg_provider, event_id)` 멱등 적재, **환불/취소 웹훅 → 결제 취소 + 구독 자동 해지 + Outbox + 감사 로그** |
| **정합성 점검** | PG 상태 ↔ 원장 대사, 불일치 감지·자동 보정, `reconciliation_logs` 기록 |
| **멀티 프로젝트** | `projects` + API Key(SHA-256 해시 저장) + 프로젝트별 웹훅. 요청 스코프 격리 확장 지점 |

---

## 왜 만드는가

1. **회사에서 부족했던 구독 기능을 직접 보강한다.**
   정기배송·구독 서비스를 운영하면서 "있으면 좋겠다"가 아니라 "없어서 사고가 난다"고 느낀 것들 —
   중복 결제, 연체 처리 누락, 청구일 누락, 해지했는데 계속 결제되는 일 — 을 도메인 규칙과 DB 제약으로 못 박는다.
2. **Node.js가 아닌 기술로 고신뢰 구독 시스템을 검증한다.**
   Java 21 + Spring Boot 3.4에서 트랜잭션 경계, 낙관적/비관적 락, 분산 락, Outbox 패턴이
   실제로 어떤 코드 모양이 되고 어디서 실패하며 어떻게 복구하는지를 직접 확인한다.
3. **다른 개발자가 내 서비스에 붙일 수 있는 오픈소스 구독 SaaS로 만든다.**
   특정 회사 내부 도구가 아니라 멀티 프로젝트를 전제로 한 제품이 되도록 설계한다.

---

## 기술 스택

| 구분 | 선택 | 이유 |
| --- | --- | --- |
| 언어/런타임 | Java 21 | Records, 패턴 매칭, 가상 스레드로 도메인 코드를 짧고 안전하게 |
| 프레임워크 | Spring Boot 3.4.7 | 트랜잭션·JPA·Actuator·검증의 표준 조합 |
| 빌드 | Gradle 8.14.3 (Kotlin DSL) + Wrapper | 재현 가능한 빌드, JDK 21 toolchain 고정 |
| DB | PostgreSQL 16 | `JSONB`, 부분 유니크 인덱스, `timestamptz` |
| 마이그레이션 | Flyway (`V1__init.sql`, `V2__core_engine_and_audit.sql`) | 스키마의 단일 출처. JPA는 `ddl-auto: validate`로만 검증 |
| 캐시/락 | Redis 7 + Redisson | 구독 단위 분산 락 (테스트는 인메모리 구현으로 대체) |
| 관측 | Spring Boot Actuator | 헬스·메트릭 |
| 테스트 | JUnit 5 + AssertJ + H2 + MockMvc | 도메인 단위 / 통합 / API 슬라이스 |

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

CANCELED ──▶ ACTIVE                          (관리자 복구)
```

- `SubscriptionStatus`가 전이 매트릭스의 **단일 출처**다.
- 상태 변경은 `Subscription`의 도메인 메서드(`recordBillingSuccess`, `registerPaymentFailure`,
  `cancel`, `restore`, `applyPendingCancellation`, `pause`, `resume`, `expire`)로만 한다.
- 잘못된 전이는 `InvalidStateTransitionException` → HTTP 409.

### 2) 중복 결제 3중 방어 — 락은 마지막 방어선의 앞단일 뿐

| 방어선 | 위치 | 막는 것 |
| --- | --- | --- |
| Redisson 분산 락 `subflow:lock:sub:{id}` | Redis (테스트: 인메모리) | 여러 인스턴스의 배치/당겨받기가 같은 구독을 동시에 청구 |
| `findByIdForUpdate` (PESSIMISTIC_WRITE) | DB 행 락 | 같은 구독에 대한 동시 상태 변경 |
| `payments.idempotency_key` UNIQUE | DB | 같은 회차·같은 재시도 번호의 중복 승인 |
| 중복 결제 가드 (`last_billed_at`) | 도메인 | 같은 날/보호 윈도우 안의 의도치 않은 재청구 |
| `webhook_inbox(pg_provider, event_id)` UNIQUE | DB | PG 웹훅 재전송 중복 처리 |

락이 만료되거나 인스턴스가 늘어도 DB 제약이 최종적으로 중복을 막는다.
`LockProvider` 추상화로 **Redis 없이도 결제 코어 전체를 테스트**할 수 있다(`subflow.lock.provider=in-memory`).

### 3) 결제 결과 3분류 — 성공 / 실패 / 미확정

PG 응답을 받은 그 자리에서 원장과 구독을 같은 트랜잭션으로 갱신한다.

- **성공**: `payments = SUCCEEDED`, `billing_cycle_count + 1`, `last_billed_at = now`,
  `next_billing_at = 기준일 기준 다음 주기`, Outbox `payment.succeeded` + `subscription.renewed`.
- **실패**: `payment_attempts` 기록 → `registerPaymentFailure` → `PAST_DUE` 또는 `EXPIRED`,
  Outbox `payment.failed` + `subscription.past_due`/`subscription.expired`.
  **이때 구독이 이미 CANCELED면 Dunning을 걸지 않는다**(추가 결제 방지, 감사 로그 `DUNNING_EXCLUDED_CANCELED`).
- **미확정(타임아웃)**: 실패로 단정하면 실제 승인된 고객에게 재청구가 나간다.
  그래서 `PENDING` + `PG_TIMEOUT` 으로 남기고, Dunning 횟수도 올리지 않으며,
  정합성 점검이 PG 실제 상태로 확정한다.

### 4) 기준일(anchor day) — 주기를 바꿔도 청구일이 튀지 않게

- 월 단위 주기는 가입일(또는 변경일)의 일을 기준일로 잡고, 말일(31일 → 2/28)은 보정한다.
- **기간의 끝**(`nextCycleEnd`)과 **다음 결제일**(`nextChargeDate`)을 구분한다.
  - 기간의 끝: 기준 시점 + 한 주기 → 기준일 정렬 (한 주기보다 짧아지지 않는다)
  - 다음 결제일: 지금 이후 가장 가까운 기준일 (일시정지 해제·복구·결제일 변경)
- 주 단위(`WEEKLY`/`BIWEEKLY`/`FOUR_WEEKLY`) ↔ 월 단위 변경 시 기준일이 **명시적으로** 생기거나 사라지고,
  변경 전에 `GET /subscriptions/{id}/cycle-change/preview` 로 다음 결제일을 미리 확인할 수 있다.

### 5) Transactional Outbox — 커밋과 이벤트 발행의 원자성

도메인 변경과 같은 트랜잭션으로 `outbox_events`에 적재하고, 발행기가 `PENDING`을 오래된 순으로 가져가
프로젝트 `webhook_url`로 보낸다.

- 헤더: `X-SubFlow-Event-Id`, `X-SubFlow-Event-Type`, `X-SubFlow-Signature: t=<epoch>,v1=<hmac-sha256>`
- 실패는 `retry_count`/`next_retry_at`(지수 백오프)/`last_error`로 남고, 한도 초과 시 `FAILED` 격리
- 수신 측(PG 웹훅)은 `webhook_inbox`에 원본을 먼저 저장하고 처리 → 재전송 안전

### 6) 정합성 점검 — "결제는 됐는데 구독이 안 열렸다"를 찾는다

`ReconciliationService`가 원장(PENDING/SUCCEEDED)을 PG 상태와 대사한다.

| 원장 | PG | 처리 |
| --- | --- | --- |
| PENDING | APPROVED | 승인 보정 + 구독 갱신 (자동) |
| PENDING | DECLINED | 실패 보정 + Dunning 반영 (자동) |
| SUCCEEDED | CANCELED | 취소 보정 + 구독 즉시 해지 (자동) |
| SUCCEEDED | UNKNOWN | **자동 보정하지 않고 불일치로 남김**(수동 확인) |

결과는 `reconciliation_logs`(검사·불일치·자동 보정 건수)에 남는다.

---

## 데이터 모델

### V1 (1단계)

| 테이블 | 역할 | 핵심 제약 |
| --- | --- | --- |
| `plans` | 상품: 가격·주기·체험일 | `code` UNIQUE, `amount >= 0` |
| `customers` | 고객(테넌트의 사용자) | `email` UNIQUE, `external_key` UNIQUE |
| `billing_keys` | PG 빌링키(카드 원본 미보관) | 고객당 기본키 1개(부분 유니크) |
| `subscriptions` | 구독 애그리거트 | 상태 CHECK, `period_end > period_start`, `version` |
| `payments` | 결제 원장(시도 1건 = 1행) | `idempotency_key` UNIQUE |
| `payment_attempts` | 승인 시도/Dunning 이력 | `(payment_id, attempt_no)` UNIQUE |
| `webhook_inbox` | 수신 웹훅 보관함 | `(pg_provider, event_id)` UNIQUE |
| `outbox_events` | 발행 대기 이벤트 | 상태 CHECK, 부분 인덱스(`PENDING`) |
| `reconciliation_logs` | 정합성 점검 결과 | 건수 CHECK |

### V2 (2단계)

| 테이블/컬럼 | 역할 |
| --- | --- |
| `projects` | 테넌트. `api_key_prefix`/`api_key_hash`(SHA-256), `webhook_url`, `webhook_secret`, 기본 프로젝트 시드 |
| `projects`/`plans`/`customers`/`subscriptions`/`payments` 의 `public_id` (UUID) | 외부 노출 식별자. 내부 PK(BIGINT)와 분리 |
| `subscriptions.billing_cycle` | 플랜 주기 override (주기 변경용) |
| `subscriptions.anchor_day` | 월 단위 기준일 1~31 (주 단위는 NULL) |
| `subscriptions.billing_cycle_count` | 누적 정상 결제 회차 — 플랜 변경에도 유지 |
| `subscriptions.last_billed_at` | 중복 결제 가드 기준 |
| `subscriptions.quantity` | 수량. 청구 금액 = 플랜 단가 × 수량 |
| `subscriptions.cancel_reason` | 해지 사유 |
| `subscription_change_histories` | 변경 종류/전후 스냅샷(JSONB)/적용 시점/사유/행위자 |
| `audit_logs` | `actor_type`·`actor_id`(VARCHAR 통일)·`target_id`(UUID)·`action`·`metadata` |

설계 메모:

- **카드 정보는 저장하지 않는다.** PG 빌링키 토큰과 표시용 마지막 4자리만 보관.
- **금액은 `numeric(12,2)`**, 통화는 ISO 4217. 결제 원장이 금액을 보관하므로 플랜 가격이 바뀌어도 과거 결제는 불변.
- **시각은 전부 `timestamptz`(UTC)**. 표시 시간대 변환은 API 계층에서.
- **식별자 혼용 방지**: 감사 로그의 `target_id`는 UUID(`public_id`)로 통일하고, `actor_id`는 문자열 규격.

---

## API

### 구독·결제

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/v1/plans` · GET `/api/v1/plans` · POST `/api/v1/plans/{id}/deactivate` | 플랜 생성/조회/단종 |
| POST | `/api/v1/customers` · POST `/api/v1/customers/{id}/billing-keys` | 고객·빌링키 등록 |
| POST | `/api/v1/subscriptions` | 구독 생성(단종 플랜 차단) |
| GET | `/api/v1/subscriptions/{id}` · `?customerId=` | 구독 조회 |
| POST | `/api/v1/subscriptions/{id}/renew` | 당겨받기(즉시 갱신, 당일 중복 409) |
| GET | `/api/v1/subscriptions/{id}/cycle-change/preview?billingCycle=` | 주기 변경 미리보기 |
| POST | `/api/v1/subscriptions/{id}/cycle-change` | 주기 변경 + 기준일 재계산 |
| POST | `/api/v1/subscriptions/{id}/plan-change` | 플랜 변경(회차 유지) |
| POST | `/api/v1/subscriptions/{id}/quantity-change` | 수량 변경 |
| POST | `/api/v1/subscriptions/{id}/billing-date-change` | 결제일(기준일) 변경 |
| POST | `/api/v1/subscriptions/{id}/cancel` · `/restore` | 해지(사유) / 관리자 복구 |
| GET | `/api/v1/subscriptions/{id}/history` | 변경 이력(before/after) |

### 웹훅·운영·프로젝트

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/v1/webhooks/{provider}` | PG 웹훅(멱등). 환불/취소 → 구독 자동 해지 |
| GET | `/api/v1/admin/summary` · `/audit-logs?limit=` | 현황·감사 로그 |
| POST | `/api/v1/admin/billing/run` · `/billing/dunning` · `/subscriptions/apply-cancellations` | 배치 수동 실행 |
| POST | `/api/v1/admin/reconciliation/run` · `/outbox/publish` | 정합성 점검 · Outbox 발행 |
| POST/GET | `/api/v1/projects` | 프로젝트 생성(API Key 1회 노출) |
| GET/POST | `/api/v1/mock-pg/status` · `/scenario` · `/reset` | Mock PG 시나리오 제어 |

행위자는 `X-Actor-Type`(USER/ADMIN/SYSTEM) · `X-Actor-Id` 헤더로 표기한다. 인증은 `X-Api-Key`(`subflow.api-key.required=true`).

오류 응답은 모두 같은 형태다.

```json
{
  "timestamp": "2026-11-25T06:00:00Z",
  "status": 409,
  "code": "DUPLICATE_BILLING_ATTEMPT",
  "message": "이미 결제된 구독입니다. 마지막 결제: 2026-12-25T00:00:00Z, 보호 윈도우: PT24H",
  "path": "/api/v1/subscriptions/1/renew"
}
```

---

## 로컬 실행

```bash
docker compose up -d          # PostgreSQL 16 + Redis 7
cd backend && ./gradlew bootRun
curl http://localhost:8080/api/v1/health
```

기동 시 Flyway가 `V1` → `V2`를 적용하고 Hibernate가 `ddl-auto: validate`로 스키마 드리프트를 검사한다.

### 데모 시나리오 (Mock PG)

```bash
# 1) 플랜·고객·빌링키·구독 생성
curl -X POST localhost:8080/api/v1/plans -H 'Content-Type: application/json' \
  -d '{"code":"BASIC_MONTHLY","name":"베이직","amount":9900,"billingCycle":"MONTHLY","trialDays":0}'
curl -X POST localhost:8080/api/v1/customers -H 'Content-Type: application/json' \
  -d '{"email":"a@example.com","name":"테스트"}'
curl -X POST localhost:8080/api/v1/customers/1/billing-keys -H 'Content-Type: application/json' \
  -d '{"pgProvider":"MOCK","billingKeyToken":"token-1","cardLast4":"1234"}'
curl -X POST localhost:8080/api/v1/subscriptions -H 'Content-Type: application/json' \
  -d '{"customerId":1,"planId":1,"billingKeyId":1,"quantity":1}'

# 2) 당겨받기 → 두 번 호출하면 409 DUPLICATE_BILLING_ATTEMPT
curl -X POST localhost:8080/api/v1/subscriptions/1/renew
curl -X POST localhost:8080/api/v1/subscriptions/1/renew

# 3) 실패 시나리오로 Dunning 확인
curl -X POST localhost:8080/api/v1/mock-pg/scenario -H 'Content-Type: application/json' \
  -d '{"scenario":"INSUFFICIENT_FUNDS"}'
curl -X POST localhost:8080/api/v1/admin/billing/dunning
```

---

## 설정

| 키 | 기본값 | 의미 |
| --- | --- | --- |
| `subflow.dunning.max-retries` | 3 | 실패 후 재시도 한도(도달 시 만료) |
| `subflow.dunning.retry-interval` | 24h | 재시도 간격 |
| `subflow.billing.duplicate-guard-window` | 24h | 같은 구독 재청구 최소 간격 |
| `subflow.billing.batch-size` | 200 | 배치 1회 처리 구독 수 |
| `subflow.lock.provider` | `redis` | `redis`(Redisson) / `in-memory`(테스트·단일 인스턴스) |
| `subflow.outbox.max-retries` / `retry-backoff` | 5 / 30s | 웹훅 발행 재시도 |
| `subflow.webhook.transport` | `http` | `http` / `noop` |
| `subflow.scheduler.enabled` | true | 배치 스케줄러 on/off |
| `subflow.api-key.required` | false | `X-Api-Key` 인증 강제 |
| `subflow.pg.provider` | `mock` | PG 어댑터 |

---

## 테스트 현황

`./gradlew build` — **121개 테스트 통과** (Java 21 / Gradle 8.14.3, 0 실패 0 스킵)

| 테스트 | 수 | 검증 내용 |
| --- | --- | --- |
| `BillingEngineIntegrationTest` | 11 | 결제 성공 반영, 당일 중복 차단, 주기 변경 직후 보호 윈도우, Dunning 성공/한도 초과, **미결제 해지 시 재시도 중단**, PG 타임아웃 PENDING, 단종 플랜 기존 구독 유지, 체험 전환 기준일 재설정, 배치 격리 |
| `SubscriptionLifecycleIntegrationTest` | 10 | 4W→월 기준일 재계산·미리보기·이력·감사, 월→주 기준일 제거, 플랜 변경 회차 유지, 단종 차단, 수량/결제일 변경, 사유 해지, 기간 종료 해지 확정, 관리자 복구 |
| `SubscriptionApiIntegrationTest` | 5 | 생성→당겨받기→중복 409→해지→복구→이력, 단종 플랜 400, 웹훅 200/중복 200, 운영 API, 404 |
| `WebhookRefundIntegrationTest` | 5 | 환불 웹훅 → 결제 취소+구독 해지+Outbox+감사, 중복 멱등, 모르는 거래/무관 이벤트 무시, 이미 해지된 구독 안전 |
| `ReconciliationIntegrationTest` | 4 | PENDING→승인 보정+회차, PG 취소→원장 취소+구독 해지, UNKNOWN 미보정, 불일치 0건 기록 |
| `OutboxPublisherTest` | 4 | HMAC 서명 검증, 지수 백오프 → FAILED, URL 없음 건너뜀, 재시도 시각 전 미발행 |
| `BillingConcurrencyIntegrationTest` | 2 | 동시 2건 당겨받기 → 승인 1건·거절 1건, 재청구 방지 |
| `SubscriptionChangeTest` | 15 | 주기/플랜/수량/결제일 도메인 규칙, 해지 사유, 복구, 중복 가드 |
| `BillingCalendarTest` | 9 | 기준일 정렬, 말일 보정, 주↔월, 주기 변경 미리보기 |
| `MockPgClientTest` | 8 | 승인/거절/타임아웃/멱등 재응답/취소 |
| `SubscriptionStateMachineTest` | 23 | 상태 전이 매트릭스(복구 포함), Dunning, 해지, 일시정지 |
| `PersistenceMappingTest` · `SchemaParityTest` | 9 | 매핑·쿼리, **V1+V2 스키마 ↔ 엔티티 일치 검사** |
| `PaymentTest` · `OutboxEventTest` · `BillingCycleTest` · `HealthControllerTest` | 16 | 단위 규칙, 예외 응답 |

**검증 범위에 대한 정직한 메모**: 이 환경에는 Docker가 없어 실제 PostgreSQL에 Flyway를 적용하는
기동 검증은 하지 못했다. 대신 ①V1+V2 SQL과 엔티티 매핑의 테이블·컬럼 일치 검사, ②H2(PostgreSQL 호환 모드)에서
매핑·쿼리·트랜잭션·동시성 검증, ③Mock PG로 승인/거절/타임아웃/멱등 시나리오 검증을 수행했다.
실제 PG 연동 시에는 `PgClient` 구현체만 추가하면 되고, 웹훅 서명 검증·카드 교체는 3단계 범위다.

---

## 디렉토리 구조 (2단계)

```
subflow/
├── docker-compose.yml
└── backend/src/
    ├── main/java/com/subflow/
    │   ├── api/                  # Plan/Customer/Subscription/Admin/Webhook/Project/MockPg 컨트롤러, API Key 필터
    │   ├── common/error/         # BusinessException, DuplicateBillingAttempt, GlobalExceptionHandler
    │   ├── config/               # SubFlowProperties, RedissonConfig, SchedulingConfig
    │   ├── domain/
    │   │   ├── plan/             # Plan, BillingCycle, BillingCalendar(기준일 계산)
    │   │   ├── subscription/     # Subscription(변경·해지·복구), 상태머신, 변경 이력
    │   │   ├── payment/          # Payment(미확정 상태 포함), PaymentAttempt
    │   │   ├── project/          # Project(테넌트, API Key 해시)
    │   │   ├── audit/            # AuditLog, ActorType
    │   │   └── outbox/ webhook/ reconciliation/ billingkey/ customer/
    │   ├── lock/                 # LockProvider, Redisson/InMemory 구현
    │   ├── pg/                   # PgClient, MockPgClient(시나리오), 결과 모델
    │   ├── service/              # BillingExecution/BillingChargeProcessor, SubscriptionLifecycle,
    │   │                         # WebhookProcessing, Reconciliation, Outbox, Audit, Project, Plan, Customer
    │   ├── outbox/               # OutboxPublisher, WebhookSigner, 전송기
    │   └── scheduler/            # BillingScheduler(결제·Dunning·해지확정·Outbox)
    └── test/java/com/subflow/
        ├── domain/ …             # 상태머신·변경 규칙·청구 달력
        ├── service/ …            # 결제 엔진·생애주기·웹훅·정합성 통합 테스트
        ├── api/ …                # MockMvc API 통합 테스트
        └── persistence/ …        # 매핑·스키마 패리티
```

---

## 로드맵

### 3단계 — 멀티 프로젝트 SaaS 완성

- **Hosted Checkout**: 프로젝트 서버가 링크를 만들면 SubFlow가 결제 페이지를 호스팅(카드 등록·빌링키 발급)
- **실제 PG 연동**: 토스페이먼츠/포트원 어댑터, 웹훅 서명 검증, 카드 교체·부분취소
- **Quota/요금제**: 프로젝트별 월 결제 건수 제한, 사용량 리포트

### 4단계 — 운영

- OpenTelemetry 트레이싱, Prometheus/Grafana 대시보드
- 개인정보 파기 정책, 감사 로그 보존·내보내기

---

## 라이선스

오픈소스로 공개할 예정이며, 라이선스는 저장소 공개 시점에 확정한다(현재 미정).
