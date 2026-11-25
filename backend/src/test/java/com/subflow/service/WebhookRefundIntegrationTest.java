package com.subflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.subflow.domain.audit.ActorType;
import com.subflow.domain.payment.Payment;
import com.subflow.domain.payment.PaymentStatus;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.subscription.Subscription;
import com.subflow.domain.subscription.SubscriptionStatus;
import com.subflow.outbox.OutboxEventTypes;
import com.subflow.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PG 환불/취소 웹훅 통합 테스트.
 *
 * <p>전액 환불 웹훅 한 번으로 결제 취소 · 구독 자동 해지 · Outbox 발행 · 감사 로그가 이뤄지고,
 * 같은 이벤트가 다시 와도 두 번 처리되지 않는지(멱등성)를 검증한다.
 */
class WebhookRefundIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private WebhookProcessingService webhookProcessingService;

    private Payment chargedPayment(Subscription subscription) {
        BillingOutcome outcome = billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);
        return paymentRepository.findById(outcome.paymentId()).orElseThrow();
    }

    @Test
    @DisplayName("전액 환불 웹훅은 결제 취소·구독 즉시 해지·Outbox 발행·감사 로그를 한 번에 처리한다")
    void refundCancelsPaymentAndSubscription() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        Payment payment = chargedPayment(subscription);

        WebhookProcessResult result = webhookProcessingService.handle("MOCK",
                new WebhookPayload("evt-refund-1", "PAYMENT_REFUNDED", payment.getPgTxId(),
                        new BigDecimal("9900"), "고객 환불 요청"),
                DUE.plusSeconds(3600));

        assertThat(result.status()).isEqualTo(WebhookProcessStatus.PROCESSED);
        assertThat(result.subscriptionId()).isEqualTo(subscription.getId());

        Payment canceled = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(canceled.getStatus()).isEqualTo(PaymentStatus.CANCELED);

        Subscription reloaded = reload(subscription.getId());
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(reloaded.getCancelReason()).contains("PAYMENT_REFUNDED").contains("고객 환불 요청");

        assertThat(outboxEventRepository
                .findByAggregateTypeAndAggregateIdOrderByIdAsc("Payment", String.valueOf(payment.getId())))
                .extracting(event -> event.getEventType())
                .contains(OutboxEventTypes.PAYMENT_CANCELED);
        assertThat(outboxEventRepository
                .findByAggregateTypeAndAggregateIdOrderByIdAsc("Subscription", String.valueOf(subscription.getId())))
                .extracting(event -> event.getEventType())
                .contains(OutboxEventTypes.SUBSCRIPTION_CANCELED);
        assertThat(auditLogRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                "SUBSCRIPTION", subscription.getPublicId()))
                .extracting(log -> log.getAction())
                .contains("SUBSCRIPTION_AUTO_CANCELED_BY_WEBHOOK");
    }

    @Test
    @DisplayName("같은 웹훅이 재전송되어도 두 번 처리하지 않는다")
    void duplicateWebhookIsIdempotent() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        Payment payment = chargedPayment(subscription);
        WebhookPayload payload = new WebhookPayload("evt-dup-1", "PAYMENT_CANCELED",
                payment.getPgTxId(), null, "중복 테스트");

        WebhookProcessResult first = webhookProcessingService.handle("MOCK", payload, DUE.plusSeconds(600));
        long outboxAfterFirst = outboxEventRepository.count();
        long auditsAfterFirst = auditLogRepository.count();

        WebhookProcessResult second = webhookProcessingService.handle("MOCK", payload, DUE.plusSeconds(900));

        assertThat(first.status()).isEqualTo(WebhookProcessStatus.PROCESSED);
        assertThat(second.status()).isEqualTo(WebhookProcessStatus.DUPLICATE_IGNORED);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxAfterFirst);
        assertThat(auditLogRepository.count()).isEqualTo(auditsAfterFirst);
        assertThat(webhookInboxRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("모르는 PG 거래의 환불 웹훅은 무시하고 기록만 남긴다")
    void unknownTransactionIsIgnored() {
        WebhookProcessResult result = webhookProcessingService.handle("MOCK",
                new WebhookPayload("evt-unknown", "PAYMENT_REFUNDED", "mock_tx_does_not_exist", null, null),
                DUE);

        assertThat(result.status()).isEqualTo(WebhookProcessStatus.IGNORED);
        assertThat(webhookInboxRepository.count()).isEqualTo(1);
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    @DisplayName("관심 없는 이벤트 타입은 무시한다")
    void otherEventTypeIsIgnored() {
        WebhookProcessResult result = webhookProcessingService.handle("MOCK",
                new WebhookPayload("evt-other", "PAYMENT_STATUS_CHANGED", "mock_tx_1", null, null), DUE);

        assertThat(result.status()).isEqualTo(WebhookProcessStatus.IGNORED);
    }

    @Test
    @DisplayName("이미 해지된 구독의 환불 웹훅은 구독을 다시 건드리지 않는다")
    void refundOnAlreadyCanceledSubscriptionIsSafe() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        Payment payment = chargedPayment(subscription);
        lifecycleService.cancel(subscription.getId(), "미리 해지해 둠", true, ActorType.USER, "user-1",
                DUE.plusSeconds(60));
        Instant canceledAt = reload(subscription.getId()).getCanceledAt();

        WebhookProcessResult result = webhookProcessingService.handle("MOCK",
                new WebhookPayload("evt-after-cancel", "PAYMENT_CANCELED", payment.getPgTxId(), null, null),
                DUE.plusSeconds(120));

        assertThat(result.status()).isEqualTo(WebhookProcessStatus.PROCESSED);
        assertThat(reload(subscription.getId()).getCanceledAt()).isEqualTo(canceledAt);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CANCELED);
    }
}
