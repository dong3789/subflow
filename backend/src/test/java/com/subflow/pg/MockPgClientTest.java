package com.subflow.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Mock PG 시뮬레이터 단위 테스트 — 승인·거절·타임아웃·멱등 재응답·취소. */
class MockPgClientTest {

    private MockPgClient client;

    @BeforeEach
    void setUp() {
        client = new MockPgClient();
        client.reset();
    }

    private ChargeRequest request(String idempotencyKey) {
        return new ChargeRequest("billing-token", new BigDecimal("9900"), "KRW",
                "sub_1_c1", idempotencyKey, Map.of("subscriptionId", "1"));
    }

    @Test
    @DisplayName("기본 시나리오는 승인이고 거래 ID 가 발급된다")
    void approvesByDefault() {
        ChargeResult result = client.chargeWithBillingKey(request("idem-1"));

        assertThat(result.approved()).isTrue();
        assertThat(result.pgTxId()).startsWith("mock_tx_");
        assertThat(client.approvedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("예약한 시나리오는 다음 호출에만 적용된다")
    void scriptsSingleScenario() {
        client.scriptNext(MockPgScenario.CARD_LIMIT_EXCEEDED);

        ChargeResult declined = client.chargeWithBillingKey(request("idem-2"));
        ChargeResult approved = client.chargeWithBillingKey(request("idem-3"));

        assertThat(declined.approved()).isFalse();
        assertThat(declined.failureCode()).isEqualTo("CARD_LIMIT_EXCEEDED");
        assertThat(approved.approved()).isTrue();
    }

    @Test
    @DisplayName("잔액 부족·카드 정지는 재시도 가능한 실패로 표시된다")
    void marksRetryableFailures() {
        assertThat(MockPgScenario.INSUFFICIENT_FUNDS.isRetryable()).isTrue();
        assertThat(MockPgScenario.CARD_LIMIT_EXCEEDED.isRetryable()).isTrue();
        assertThat(MockPgScenario.CARD_DECLINED.isRetryable()).isFalse();
        assertThat(MockPgScenario.INVALID_BILLING_KEY.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("같은 멱등키는 두 번 승인되지 않고 같은 거래 결과를 돌려준다")
    void replaysIdempotently() {
        ChargeResult first = client.chargeWithBillingKey(request("same-key"));
        ChargeResult second = client.chargeWithBillingKey(request("same-key"));

        assertThat(second.approved()).isTrue();
        assertThat(second.pgTxId()).isEqualTo(first.pgTxId());
        assertThat(client.transactions()).hasSize(1);
        assertThat(client.approvedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("네트워크 타임아웃은 거래 ID 를 담은 예외로 던지고, PG 쪽에는 승인 거래가 남는다")
    void throwsTimeoutButRecordsApproval() {
        client.scriptNext(MockPgScenario.NETWORK_TIMEOUT);

        assertThatThrownBy(() -> client.chargeWithBillingKey(request("timeout-key")))
                .isInstanceOf(PgTimeoutException.class)
                .satisfies(error -> {
                    PgTimeoutException timeout = (PgTimeoutException) error;
                    assertThat(timeout.getOrderId()).isEqualTo("sub_1_c1");
                    assertThat(timeout.getPgTxId()).startsWith("mock_tx_");
                });

        List<MockPgClient.MockTransaction> transactions = client.transactions();
        assertThat(transactions).hasSize(1);
        assertThat(transactions.get(0).status()).isEqualTo(PgTransactionStatus.APPROVED);
    }

    @Test
    @DisplayName("타임아웃 결과를 미확정(UNKNOWN)으로 바꿀 수 있다")
    void timeoutCanLeaveUnknownState() {
        client.setTimeoutOutcome(PgTransactionStatus.UNKNOWN);
        client.scriptNext(MockPgScenario.NETWORK_TIMEOUT);

        assertThatThrownBy(() -> client.chargeWithBillingKey(request("timeout-unknown")))
                .isInstanceOf(PgTimeoutException.class);

        String txId = client.findByIdempotencyKey("timeout-unknown").orElseThrow().txId();
        assertThat(client.fetchPaymentStatus(txId).status()).isEqualTo(PgTransactionStatus.UNKNOWN);
    }

    @Test
    @DisplayName("승인 거래는 취소할 수 있고, 없는 거래는 취소 실패로 응답한다")
    void cancelsApprovedTransaction() {
        String txId = client.chargeWithBillingKey(request("cancel-key")).pgTxId();

        CancelResult canceled = client.cancelPayment(txId, "고객 요청");
        CancelResult unknown = client.cancelPayment("mock_tx_9999", "없는 거래");

        assertThat(canceled.canceled()).isTrue();
        assertThat(client.fetchPaymentStatus(txId).status()).isEqualTo(PgTransactionStatus.CANCELED);
        assertThat(unknown.canceled()).isFalse();
        assertThat(unknown.code()).isEqualTo("TX_NOT_FOUND");
    }

    @Test
    @DisplayName("모르는 거래 조회는 UNKNOWN 으로 응답한다")
    void returnsUnknownForMissingTransaction() {
        assertThat(client.fetchPaymentStatus("nope").status()).isEqualTo(PgTransactionStatus.UNKNOWN);
    }
}
