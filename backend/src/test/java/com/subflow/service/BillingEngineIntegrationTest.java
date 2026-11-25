package com.subflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subflow.common.error.DuplicateBillingAttemptException;
import com.subflow.domain.payment.Payment;
import com.subflow.domain.payment.PaymentStatus;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.subscription.Subscription;
import com.subflow.domain.subscription.SubscriptionStatus;
import com.subflow.outbox.OutboxEventTypes;
import com.subflow.pg.MockPgScenario;
import com.subflow.support.IntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 정기결제 코어 엔진 통합 테스트.
 *
 * <p>검증 축: 결제 성공 처리, 중복 결제 가드(당일·보호 윈도우), Dunning 재시도/만료,
 * 미결제 해지 시 Dunning 제외, PG 타임아웃(미확정) 처리.
 */
class BillingEngineIntegrationTest extends IntegrationTestSupport {

    private static final Instant RETRY_1 = DUE.plusSeconds(3600);

    @Test
    @DisplayName("만기 결제 성공 시 원장·회차·다음 청구일·Outbox 가 한 트랜잭션으로 반영된다")
    void billingSuccessAdvancesCycle() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);

        BillingOutcome outcome = billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);

        assertThat(outcome.status()).isEqualTo(BillingResultStatus.SUCCEEDED);
        assertThat(outcome.paymentId()).isNotNull();

        Subscription reloaded = reload(subscription.getId());
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(reloaded.getBillingCycleCount()).isEqualTo(1);
        assertThat(reloaded.getLastBilledAt()).isEqualTo(DUE);
        assertThat(reloaded.getNextBillingAt()).isEqualTo(Instant.parse("2027-01-25T00:00:00Z"));

        Payment payment = paymentRepository.findById(outcome.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getAmount()).isEqualByComparingTo("9900");
        assertThat(payment.getPaidAt()).isEqualTo(DUE);
        assertThat(paymentAttemptRepository.countByPaymentId(payment.getId())).isEqualTo(1);

        List<String> eventTypes = outboxEventRepository
                .findByAggregateTypeAndAggregateIdOrderByIdAsc("Payment", String.valueOf(payment.getId()))
                .stream().map(event -> event.getEventType()).toList();
        assertThat(eventTypes).contains(OutboxEventTypes.PAYMENT_SUCCEEDED);
        assertThat(outboxEventRepository
                .findByAggregateTypeAndAggregateIdOrderByIdAsc("Subscription", String.valueOf(subscription.getId())))
                .extracting(event -> event.getEventType())
                .contains(OutboxEventTypes.SUBSCRIPTION_RENEWED);
    }

    @Test
    @DisplayName("당일 당겨받기는 중복 결제로 차단되고 결제는 1건만 남는다")
    void blocksSameDayImmediateRenewal() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);

        assertThatThrownBy(() -> billingService.renewNow(subscription.getId(), DUE.plusSeconds(3600)))
                .isInstanceOf(DuplicateBillingAttemptException.class)
                .hasMessageContaining("이미 결제된 구독");

        assertThat(paymentRepository.findBySubscriptionIdOrderByIdDesc(subscription.getId())).hasSize(1);
        assertThat(mockPgClient.approvedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("주기 변경 직후 보호 윈도우 안의 재청구도 차단된다")
    void blocksRechargeRightAfterCycleChange() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);

        lifecycleService.changeCycle(subscription.getId(), BillingCycle.WEEKLY, com.subflow.domain.subscription.EffectiveMode.IMMEDIATE,
                "주기 변경 후 즉시 재청구 시도", com.subflow.domain.audit.ActorType.ADMIN, "admin-1",
                DUE.plusSeconds(600));

        assertThatThrownBy(() -> billingService.renewNow(subscription.getId(), DUE.plusSeconds(900)))
                .isInstanceOf(DuplicateBillingAttemptException.class);

        assertThat(paymentRepository.findBySubscriptionIdOrderByIdDesc(subscription.getId())).hasSize(1);
    }

    @Test
    @DisplayName("결제 거절은 연체(PAST_DUE)로 기록되고 재시도 시각이 잡힌다")
    void declineMovesToPastDue() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        mockPgClient.scriptNext(MockPgScenario.INSUFFICIENT_FUNDS);

        BillingOutcome outcome = billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);

        assertThat(outcome.status()).isEqualTo(BillingResultStatus.FAILED);
        Subscription reloaded = reload(subscription.getId());
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
        assertThat(reloaded.getRetryCount()).isEqualTo(1);
        assertThat(reloaded.getNextBillingAt()).as("테스트 프로파일 재시도 간격 1시간").isEqualTo(RETRY_1);

        Payment payment = paymentRepository.findById(outcome.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("Dunning 재시도에서 성공하면 ACTIVE 로 복귀하고 회차가 올라간다")
    void dunningRetrySucceeds() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        mockPgClient.scriptNext(MockPgScenario.INSUFFICIENT_FUNDS);
        billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);

        BillingOutcome retry = billingService.charge(subscription.getId(), BillingReason.DUNNING_RETRY, RETRY_1);

        assertThat(retry.status()).isEqualTo(BillingResultStatus.SUCCEEDED);
        Subscription reloaded = reload(subscription.getId());
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(reloaded.getRetryCount()).isZero();
        assertThat(reloaded.getBillingCycleCount()).isEqualTo(1);
        assertThat(paymentRepository.findBySubscriptionIdOrderByIdDesc(subscription.getId())).hasSize(2);
    }

    @Test
    @DisplayName("재시도 한도를 넘으면 구독이 만료되고 더 이상 청구 대상이 아니다")
    void dunningExhaustionExpires() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        mockPgClient.scriptNext(3, MockPgScenario.CARD_DECLINED);

        BillingOutcome first = billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);
        BillingOutcome second = billingService.charge(subscription.getId(), BillingReason.DUNNING_RETRY,
                DUE.plusSeconds(3600));
        BillingOutcome third = billingService.charge(subscription.getId(), BillingReason.DUNNING_RETRY,
                DUE.plusSeconds(7200));

        assertThat(first.status()).isEqualTo(BillingResultStatus.FAILED);
        assertThat(second.status()).isEqualTo(BillingResultStatus.FAILED);
        assertThat(third.status()).isEqualTo(BillingResultStatus.EXPIRED);

        Subscription reloaded = reload(subscription.getId());
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(reloaded.getRetryCount()).isEqualTo(3);
        assertThat(reloaded.getNextBillingAt()).isNull();
        assertThat(outboxEventRepository
                .findByAggregateTypeAndAggregateIdOrderByIdAsc("Subscription", String.valueOf(subscription.getId())))
                .extracting(event -> event.getEventType())
                .contains(OutboxEventTypes.SUBSCRIPTION_EXPIRED);
    }

    @Test
    @DisplayName("미결제(연체) 상태에서 해지하면 Dunning 재시도 대상에서 즉시 빠지고 추가 결제가 없다")
    void cancelDuringDunningStopsRetries() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        mockPgClient.scriptNext(MockPgScenario.INSUFFICIENT_FUNDS);
        billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);
        assertThat(reload(subscription.getId()).getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);

        lifecycleService.cancel(subscription.getId(), "고객이 해지 요청", true,
                com.subflow.domain.audit.ActorType.USER, "user-1", DUE.plusSeconds(60));

        assertThat(reload(subscription.getId()).getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(subscriptionRepository.findDuePastDue(RETRY_1,
                org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();
        assertThat(billingService.runDunningRetry(RETRY_1).total()).isZero();

        BillingOutcome outcome = billingService.charge(subscription.getId(), BillingReason.DUNNING_RETRY, RETRY_1);
        assertThat(outcome.status()).isEqualTo(BillingResultStatus.SKIPPED_NOT_BILLABLE);
        assertThat(paymentRepository.findBySubscriptionIdOrderByIdDesc(subscription.getId())).hasSize(1);
        assertThat(mockPgClient.transactions()).hasSize(1);
    }

    @Test
    @DisplayName("PG 타임아웃은 PENDING(미확정)으로 남기고 재청구하지 않는다")
    void timeoutLeavesPaymentUncertain() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        mockPgClient.scriptNext(MockPgScenario.NETWORK_TIMEOUT);

        BillingOutcome outcome = billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);

        assertThat(outcome.status()).isEqualTo(BillingResultStatus.PENDING_CONFIRMATION);
        assertThat(outcome.pgTxId()).startsWith("mock_tx_");

        Payment payment = paymentRepository.findById(outcome.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.isUncertain()).isTrue();
        assertThat(payment.getFailureCode()).isEqualTo("PG_TIMEOUT");

        Subscription reloaded = reload(subscription.getId());
        assertThat(reloaded.getStatus()).as("승인 여부를 모르므로 구독 상태를 바꾸지 않는다")
                .isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(reloaded.getBillingCycleCount()).isZero();
        assertThat(reloaded.getLastBilledAt()).as("중복 결제 가드를 걸지 않는다").isNull();

        BillingOutcome second = billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);
        assertThat(second.status()).as("같은 멱등키는 재승인하지 않는다")
                .isEqualTo(BillingResultStatus.PENDING_CONFIRMATION);
        assertThat(mockPgClient.transactions()).hasSize(1);
    }

    @Test
    @DisplayName("단종된 플랜의 기존 구독은 정기결제가 계속된다")
    void inactivePlanStillBillsExistingSubscription() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        var plan = planRepository.findById(subscription.getPlan().getId()).orElseThrow();
        plan.deactivate();
        planRepository.save(plan);

        BillingOutcome outcome = billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);

        assertThat(outcome.status()).isEqualTo(BillingResultStatus.SUCCEEDED);
        assertThat(reload(subscription.getId()).getBillingCycleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("체험 종료 후 첫 결제는 기준일을 결제일 기준으로 다시 잡는다")
    void trialConversionResetsAnchor() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        // 체험 14일 플랜으로 별도 구독 생성
        var trialPlan = createPlan("19900", BillingCycle.MONTHLY, 14);
        var customer = createCustomer();
        var billingKey = createBillingKey(customer);
        var trial = createSubscription(trialPlan, customer, billingKey, T0);
        Instant trialEnd = trial.getCurrentPeriodEnd();

        BillingOutcome outcome = billingService.charge(trial.getId(), BillingReason.SCHEDULED, trialEnd);

        assertThat(outcome.status()).isEqualTo(BillingResultStatus.SUCCEEDED);
        Subscription reloaded = reload(trial.getId());
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(reloaded.getAnchorDay()).isEqualTo(9);
        assertThat(reloaded.getNextBillingAt()).isEqualTo(Instant.parse("2027-01-09T00:00:00Z"));
        assertThat(subscription.getId()).isNotEqualTo(trial.getId());
    }

    @Test
    @DisplayName("배치는 만기 구독만 골라 청구하고 건별 실패를 격리한다")
    void batchBillsOnlyDueSubscriptions() {
        Subscription due = createActiveSubscription("9900", BillingCycle.MONTHLY);
        Subscription notDue = createActiveSubscription("4900", BillingCycle.YEARLY);

        BillingBatchResult result = billingService.runDueBilling(DUE);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(reload(due.getId()).getBillingCycleCount()).isEqualTo(1);
        assertThat(reload(notDue.getId()).getBillingCycleCount()).isZero();
    }
}
