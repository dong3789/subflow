package com.subflow.pg;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock PG 시뮬레이터.
 *
 * <p>실제 PG 없이 결제 코어를 끝까지 검증하기 위한 구현이다. 지원하는 시나리오:
 * <ul>
 *   <li>정상 승인</li>
 *   <li>잔액 부족 / 카드 한도 초과 (일시 실패 → Dunning 재시도로 회복)</li>
 *   <li>카드 정지 / 빌링키 무효 (재시도 무의미)</li>
 *   <li>네트워크 타임아웃 (승인 여부 미확정 → 원장 PENDING → 정합성 점검으로 확정)</li>
 *   <li>이미 처리된 멱등 응답 (같은 멱등키는 같은 거래 결과를 돌려주고 두 번 승인하지 않는다)</li>
 * </ul>
 *
 * <p>시나리오는 {@link #scriptNext(MockPgScenario)} 로 다음 호출에만, {@link #setDefaultScenario(MockPgScenario)}
 * 로 이후 모든 호출에 적용한다.
 */
@Component
@ConditionalOnProperty(name = "subflow.pg.provider", havingValue = "mock", matchIfMissing = true)
public class MockPgClient implements PgClient {

    private static final String PROVIDER = "MOCK";

    private final AtomicReference<MockPgScenario> defaultScenario =
            new AtomicReference<>(MockPgScenario.APPROVE);
    private final Queue<MockPgScenario> scripted = new ConcurrentLinkedQueue<>();
    private final Map<String, MockTransaction> byTxId = new ConcurrentHashMap<>();
    private final Map<String, String> txIdByIdempotencyKey = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1);
    /** 타임아웃 시나리오에서 PG 가 실제로 승인했는지 여부(기본: 승인됨 — 가장 까다로운 경우). */
    private final AtomicReference<PgTransactionStatus> timeoutOutcome =
            new AtomicReference<>(PgTransactionStatus.APPROVED);

    // ------------------------------------------------------------------
    // PgClient
    // ------------------------------------------------------------------

    @Override
    public ChargeResult chargeWithBillingKey(ChargeRequest request) {
        String existingTxId = txIdByIdempotencyKey.get(request.idempotencyKey());
        if (existingTxId != null) {
            MockTransaction existing = byTxId.get(existingTxId);
            return new ChargeResult(existing.status().isPaid(), existing.txId(),
                    existing.failureCode(), existing.failureReason(), existing.status());
        }

        MockPgScenario scenario = nextScenario();
        String txId = "mock_tx_" + sequence.getAndIncrement();

        switch (scenario) {
            case APPROVE -> {
                save(new MockTransaction(txId, request.idempotencyKey(), request.orderId(),
                        request.amount(), request.currency(), PgTransactionStatus.APPROVED, null, null));
                return ChargeResult.approved(txId);
            }
            case NETWORK_TIMEOUT -> {
                save(new MockTransaction(txId, request.idempotencyKey(), request.orderId(),
                        request.amount(), request.currency(), timeoutOutcome.get(), null, null));
                throw new PgTimeoutException(request.orderId(), txId,
                        "PG 응답 타임아웃: 승인 여부 미확정 (orderId=" + request.orderId() + ")");
            }
            default -> {
                MockTransaction declined = new MockTransaction(txId, request.idempotencyKey(), request.orderId(),
                        request.amount(), request.currency(), PgTransactionStatus.DECLINED,
                        scenario.getCode(), "Mock PG 거절: " + scenario.getCode());
                save(declined);
                return ChargeResult.declined(txId, scenario.getCode(), declined.failureReason());
            }
        }
    }

    @Override
    public CancelResult cancelPayment(String pgTxId, String reason) {
        MockTransaction tx = byTxId.get(pgTxId);
        if (tx == null) {
            return CancelResult.failed(pgTxId, "TX_NOT_FOUND", "PG 에 해당 거래가 없습니다: " + pgTxId);
        }
        if (tx.status() == PgTransactionStatus.DECLINED) {
            return CancelResult.failed(pgTxId, "TX_DECLINED", "승인되지 않은 거래는 취소할 수 없습니다.");
        }
        if (tx.status() == PgTransactionStatus.CANCELED) {
            return CancelResult.success(pgTxId);
        }
        save(tx.withStatus(PgTransactionStatus.CANCELED));
        return CancelResult.success(pgTxId);
    }

    @Override
    public PaymentStatusResult fetchPaymentStatus(String pgTxId) {
        MockTransaction tx = byTxId.get(pgTxId);
        if (tx == null) {
            return PaymentStatusResult.unknown(pgTxId);
        }
        return new PaymentStatusResult(tx.txId(), tx.orderId(), tx.status(), tx.amount(), tx.currency());
    }

    // ------------------------------------------------------------------
    // 시나리오 제어 / 조회 (테스트·데모용)
    // ------------------------------------------------------------------

    /** 다음 승인 요청 1건에만 적용할 시나리오. */
    public void scriptNext(MockPgScenario scenario) {
        scripted.add(scenario);
    }

    /** 다음 승인 요청 n건에 적용할 시나리오. */
    public void scriptNext(int count, MockPgScenario scenario) {
        for (int i = 0; i < count; i++) {
            scripted.add(scenario);
        }
    }

    /** 이후 모든 승인 요청의 기본 시나리오. */
    public void setDefaultScenario(MockPgScenario scenario) {
        defaultScenario.set(scenario);
    }

    public MockPgScenario getDefaultScenario() {
        return defaultScenario.get();
    }

    /** 타임아웃 시 PG 가 실제로 승인했는지 설정한다(기본 APPROVED). */
    public void setTimeoutOutcome(PgTransactionStatus status) {
        timeoutOutcome.set(status);
    }

    /** 예약된 시나리오와 거래 내역을 모두 지운다(테스트 격리). */
    public void reset() {
        scripted.clear();
        defaultScenario.set(MockPgScenario.APPROVE);
        timeoutOutcome.set(PgTransactionStatus.APPROVED);
        byTxId.clear();
        txIdByIdempotencyKey.clear();
        sequence.set(1);
    }

    public Optional<MockTransaction> findByIdempotencyKey(String idempotencyKey) {
        String txId = txIdByIdempotencyKey.get(idempotencyKey);
        return txId == null ? Optional.empty() : Optional.ofNullable(byTxId.get(txId));
    }

    /** 지금까지 PG 가 처리한 거래(정합성 대사 테스트용). */
    public List<MockTransaction> transactions() {
        return List.copyOf(byTxId.values());
    }

    public long approvedCount() {
        return byTxId.values().stream().filter(tx -> tx.status() == PgTransactionStatus.APPROVED).count();
    }

    private MockPgScenario nextScenario() {
        MockPgScenario scriptedScenario = scripted.poll();
        return scriptedScenario != null ? scriptedScenario : defaultScenario.get();
    }

    private void save(MockTransaction tx) {
        byTxId.put(tx.txId(), tx);
        txIdByIdempotencyKey.put(tx.idempotencyKey(), tx.txId());
    }

    /**
     * PG 쪽 거래 1건.
     *
     * @param txId           PG 거래 ID
     * @param idempotencyKey 승인 요청 멱등키
     * @param orderId        가맹점 주문 ID
     * @param amount         금액
     * @param currency       통화
     * @param status         PG 상태
     * @param failureCode    거절 코드
     * @param failureReason  거절 사유
     */
    public record MockTransaction(String txId, String idempotencyKey, String orderId, BigDecimal amount,
                                  String currency, PgTransactionStatus status,
                                  String failureCode, String failureReason) {

        MockTransaction withStatus(PgTransactionStatus newStatus) {
            return new MockTransaction(txId, idempotencyKey, orderId, amount, currency,
                    newStatus, failureCode, failureReason);
        }

        public String provider() {
            return PROVIDER;
        }
    }
}
