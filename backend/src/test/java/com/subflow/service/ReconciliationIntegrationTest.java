package com.subflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.subflow.domain.payment.Payment;
import com.subflow.domain.payment.PaymentStatus;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.reconciliation.ReconciliationLog;
import com.subflow.domain.subscription.Subscription;
import com.subflow.domain.subscription.SubscriptionStatus;
import com.subflow.pg.MockPgScenario;
import com.subflow.pg.PgTransactionStatus;
import com.subflow.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 정합성 점검(PG ↔ 원장 대사) 통합 테스트.
 *
 * <p>PG 타임아웃으로 미확정(PENDING)으로 남은 결제를 PG 실제 상태로 확정하고,
 * PG 가 취소한 결제를 발견해 구독까지 정리하는지 확인한다. 보정 결과는 점검 로그에 남는다.
 */
class ReconciliationIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("PG 승인으로 확인된 PENDING 결제는 승인 보정되고 구독 회차도 함께 올라간다")
    void fixesPendingPaymentApprovedByPg() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        mockPgClient.scriptNext(MockPgScenario.NETWORK_TIMEOUT);
        BillingOutcome outcome = billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);
        assertThat(outcome.status()).isEqualTo(BillingResultStatus.PENDING_CONFIRMATION);

        ReconciliationResult result = reconciliationService.run(DUE.plusSeconds(3600));

        assertThat(result.checkedCount()).isEqualTo(1);
        assertThat(result.mismatchCount()).isEqualTo(1);
        assertThat(result.autoFixedCount()).isEqualTo(1);
        assertThat(result.unresolvedCount()).isZero();

        Payment fixed = paymentRepository.findById(outcome.paymentId()).orElseThrow();
        assertThat(fixed.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(fixed.getPaidAt()).isNotNull();

        Subscription reloaded = reload(subscription.getId());
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(reloaded.getBillingCycleCount()).isEqualTo(1);

        ReconciliationLog saved = reconciliationLogRepository.findById(result.logId()).orElseThrow();
        assertThat(saved.hasMismatch()).isTrue();
        assertThat(saved.getAutoFixedCount()).isEqualTo(1);
        assertThat(saved.getSummary()).contains("자동 보정");
    }

    @Test
    @DisplayName("PG 가 취소한 승인 결제를 발견하면 원장 취소와 구독 해지까지 보정한다")
    void fixesSucceededPaymentCanceledByPg() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        BillingOutcome outcome = billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);
        Payment payment = paymentRepository.findById(outcome.paymentId()).orElseThrow();
        mockPgClient.cancelPayment(payment.getPgTxId(), "PG 측 취소");

        ReconciliationResult result = reconciliationService.run(DUE.plusSeconds(7200));

        assertThat(result.mismatchCount()).isEqualTo(1);
        assertThat(result.autoFixedCount()).isEqualTo(1);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CANCELED);
        assertThat(reload(subscription.getId()).getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
    }

    @Test
    @DisplayName("PG 상태가 미확정(UNKNOWN)이면 자동 보정하지 않고 불일치로 남긴다")
    void leavesUnknownStatusUnresolved() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        mockPgClient.setTimeoutOutcome(PgTransactionStatus.UNKNOWN);
        mockPgClient.scriptNext(MockPgScenario.NETWORK_TIMEOUT);
        billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);

        ReconciliationResult result = reconciliationService.run(DUE.plusSeconds(3600));

        assertThat(result.mismatchCount()).isEqualTo(1);
        assertThat(result.autoFixedCount()).isZero();
        assertThat(result.unresolvedCount()).isEqualTo(1);
        assertThat(paymentRepository.findBySubscriptionIdOrderByIdDesc(subscription.getId()).get(0).getStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("불일치가 없으면 점검 로그에 0건으로 남는다")
    void recordsCleanRun() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);

        ReconciliationResult result = reconciliationService.run(DUE.plusSeconds(600));

        assertThat(result.mismatchCount()).isZero();
        assertThat(result.autoFixedCount()).isZero();
        assertThat(reconciliationLogRepository.findById(result.logId()).orElseThrow().getSummary())
                .contains("불일치 없음");
    }
}
