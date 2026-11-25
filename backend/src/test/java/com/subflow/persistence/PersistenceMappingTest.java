package com.subflow.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subflow.domain.billingkey.BillingKey;
import com.subflow.domain.billingkey.BillingKeyRepository;
import com.subflow.domain.customer.Customer;
import com.subflow.domain.customer.CustomerRepository;
import com.subflow.domain.outbox.OutboxEvent;
import com.subflow.domain.outbox.OutboxEventRepository;
import com.subflow.domain.outbox.OutboxStatus;
import com.subflow.domain.payment.Payment;
import com.subflow.domain.payment.PaymentAttempt;
import com.subflow.domain.payment.PaymentAttemptRepository;
import com.subflow.domain.payment.PaymentRepository;
import com.subflow.domain.payment.PaymentStatus;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.plan.Plan;
import com.subflow.domain.plan.PlanRepository;
import com.subflow.domain.reconciliation.ReconciliationLog;
import com.subflow.domain.reconciliation.ReconciliationLogRepository;
import com.subflow.domain.subscription.Subscription;
import com.subflow.domain.subscription.SubscriptionRepository;
import com.subflow.domain.subscription.SubscriptionStatus;
import com.subflow.domain.webhook.WebhookInbox;
import com.subflow.domain.webhook.WebhookInboxRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 엔티티 매핑과 리포지토리 쿼리 검증(H2, PostgreSQL 호환 모드).
 *
 * <p>실제 PostgreSQL이 아니라 H2로 도는 슬라이스 테스트다. PostgreSQL 전용 문법(JSONB, 부분 인덱스)은
 * {@code V1__init.sql}이 담당하고, 이 테스트는 JPA 매핑과 파생 쿼리/JPQL이 실제로 동작하는지만 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PersistenceMappingTest {

    private static final Instant T0 = Instant.parse("2026-11-25T00:00:00Z");

    @Autowired private EntityManager em;
    @Autowired private PlanRepository planRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private BillingKeyRepository billingKeyRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private WebhookInboxRepository webhookInboxRepository;
    @Autowired private ReconciliationLogRepository reconciliationLogRepository;

    private Customer persistCustomer() {
        return customerRepository.save(Customer.register("dong@example.com", "동동", "ext-1"));
    }

    private Plan persistPlan(String code, String amount, int trialDays) {
        return planRepository.save(Plan.create(code, "플랜 " + code, new BigDecimal(amount), "KRW",
                BillingCycle.MONTHLY, trialDays, "테스트 플랜"));
    }

    private Subscription persistSubscription(Customer customer, Plan plan, BillingKey billingKey) {
        return subscriptionRepository.save(Subscription.start(customer, plan, billingKey, T0));
    }

    @Test
    @DisplayName("구독 애그리거트가 상태·기간·버전과 함께 저장되고 다시 읽힌다")
    void persistsSubscription() {
        Customer customer = persistCustomer();
        Plan plan = persistPlan("BASIC_MONTHLY", "9900", 0);
        BillingKey billingKey = billingKeyRepository.save(
                BillingKey.issue(customer, "TOSS", "token-1", "1234"));

        Subscription saved = persistSubscription(customer, plan, billingKey);
        em.flush();
        em.clear();

        Subscription found = subscriptionRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(found.getCurrentPeriodStart()).isEqualTo(T0);
        assertThat(found.getCurrentPeriodEnd()).isEqualTo(Instant.parse("2026-12-25T00:00:00Z"));
        assertThat(found.getNextBillingAt()).isEqualTo(Instant.parse("2026-12-25T00:00:00Z"));
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getVersion()).isZero();
        assertThat(found.getCustomer().getEmail()).isEqualTo("dong@example.com");
        assertThat(found.getPlan().getCode()).isEqualTo("BASIC_MONTHLY");
    }

    @Test
    @DisplayName("청구 대상 배치 조회는 청구 시각이 지난 유효 구독만 돌려준다")
    void findsDueSubscriptions() {
        Customer customer = persistCustomer();
        Plan plan = persistPlan("BASIC_MONTHLY", "9900", 0);
        BillingKey billingKey = billingKeyRepository.save(
                BillingKey.issue(customer, "TOSS", "token-1", "1234"));

        Subscription due = persistSubscription(customer, plan, billingKey);
        Subscription paused = persistSubscription(customer, plan, billingKey);
        paused.pause();
        Subscription canceledLater = persistSubscription(customer, plan, billingKey);
        canceledLater.cancelAtPeriodEnd(T0);

        em.flush();
        em.clear();

        Instant periodEnd = Instant.parse("2026-12-25T00:00:00Z");

        assertThat(subscriptionRepository.findDueSubscriptions(T0, PageRequest.of(0, 10)))
                .as("청구일이 오기 전에는 대상이 없다")
                .isEmpty();

        List<Subscription> found = subscriptionRepository.findDueSubscriptions(periodEnd, PageRequest.of(0, 10));

        assertThat(found).extracting(Subscription::getId).containsExactly(due.getId());
    }

    @Test
    @DisplayName("해지 예약 후 기간이 끝난 구독을 찾아 해지 확정할 수 있다")
    void findsSubscriptionsToCancel() {
        Customer customer = persistCustomer();
        Plan plan = persistPlan("BASIC_MONTHLY", "9900", 0);
        BillingKey billingKey = billingKeyRepository.save(
                BillingKey.issue(customer, "TOSS", "token-1", "1234"));

        Subscription subscription = persistSubscription(customer, plan, billingKey);
        subscription.cancelAtPeriodEnd(T0.plusSeconds(60));
        em.flush();
        em.clear();

        assertThat(subscriptionRepository.findExpiredByCancellation(T0)).isEmpty();

        List<Subscription> targets = subscriptionRepository.findExpiredByCancellation(
                Instant.parse("2026-12-25T00:00:00Z"));
        assertThat(targets).hasSize(1);

        targets.get(0).applyPendingCancellation(Instant.parse("2026-12-25T00:00:00Z"));
        em.flush();

        assertThat(subscriptionRepository.findById(subscription.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.CANCELED);
    }

    @Test
    @DisplayName("결제 멱등키 유니크 제약이 중복 승인을 DB에서 막는다")
    void rejectsDuplicateIdempotencyKey() {
        Customer customer = persistCustomer();
        Plan plan = persistPlan("BASIC_MONTHLY", "9900", 0);
        BillingKey billingKey = billingKeyRepository.save(
                BillingKey.issue(customer, "TOSS", "token-1", "1234"));
        Subscription subscription = persistSubscription(customer, plan, billingKey);

        paymentRepository.save(Payment.request(subscription, "idem-unique", new BigDecimal("9900"), "KRW", "TOSS"));
        em.flush();

        assertThat(paymentRepository.existsByIdempotencyKey("idem-unique")).isTrue();
        assertThatThrownBy(() -> {
            paymentRepository.save(Payment.request(subscription, "idem-unique", new BigDecimal("9900"), "KRW", "TOSS"));
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("결제와 시도 이력이 순번대로 저장되고 최신 시도를 조회할 수 있다")
    void persistsPaymentsAndAttempts() {
        Customer customer = persistCustomer();
        Plan plan = persistPlan("BASIC_MONTHLY", "9900", 0);
        BillingKey billingKey = billingKeyRepository.save(
                BillingKey.issue(customer, "TOSS", "token-1", "1234"));
        Subscription subscription = persistSubscription(customer, plan, billingKey);

        Payment payment = paymentRepository.save(
                Payment.request(subscription, "idem-attempts", new BigDecimal("9900"), "KRW", "TOSS"));
        paymentAttemptRepository.save(PaymentAttempt.failure(payment, 1, "카드 한도 초과", T0));
        paymentAttemptRepository.save(PaymentAttempt.failure(payment, 2, "카드 정지", T0.plusSeconds(60)));
        payment.markSucceeded("pg-tx-1", T0.plusSeconds(120));
        paymentAttemptRepository.save(PaymentAttempt.success(payment, 3, T0.plusSeconds(120)));
        em.flush();
        em.clear();

        List<PaymentAttempt> attempts = paymentAttemptRepository.findByPaymentIdOrderByAttemptNoAsc(payment.getId());
        assertThat(attempts).extracting(PaymentAttempt::getAttemptNo).containsExactly(1, 2, 3);
        assertThat(paymentAttemptRepository.findTopByPaymentIdOrderByAttemptNoDesc(payment.getId())
                .orElseThrow().getStatus().isSuccess()).isTrue();

        Payment found = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(found.getPgTxId()).isEqualTo("pg-tx-1");
        assertThat(paymentRepository.sumSucceededAmountBetween(T0, T0.plusSeconds(3600)))
                .isEqualByComparingTo("9900");
    }

    @Test
    @DisplayName("Outbox 이벤트는 JSON 본문과 함께 저장되고 발행 대기 조회에 잡힌다")
    void persistsOutboxEvents() {
        outboxEventRepository.save(OutboxEvent.pending("Subscription", "1", "SUBSCRIPTION_STARTED",
                "{\"planCode\":\"BASIC_MONTHLY\",\"amount\":9900}"));
        OutboxEvent failed = OutboxEvent.pending("Payment", "2", "PAYMENT_FAILED", "{\"code\":\"CARD_DECLINED\"}");
        failed.markPublishFailed("broker down", T0, 1);
        outboxEventRepository.save(failed);
        em.flush();
        em.clear();

        List<OutboxEvent> publishable = outboxEventRepository.findPublishable(T0, PageRequest.of(0, 10));

        assertThat(publishable).hasSize(1);
        assertThat(publishable.get(0).getEventType()).isEqualTo("SUBSCRIPTION_STARTED");
        assertThat(publishable.get(0).getPayload()).contains("BASIC_MONTHLY");
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.FAILED)).isEqualTo(1);
    }

    @Test
    @DisplayName("웹훅 수신함은 (PG, 이벤트ID) 유니크로 중복 수신을 막는다")
    void rejectsDuplicateWebhook() {
        webhookInboxRepository.save(WebhookInbox.received("TOSS", "evt-1", "PAYMENT_STATUS_CHANGED",
                "{\"status\":\"DONE\"}", T0));
        em.flush();

        assertThat(webhookInboxRepository.existsByPgProviderAndEventId("TOSS", "evt-1")).isTrue();
        assertThatThrownBy(() -> {
            webhookInboxRepository.save(WebhookInbox.received("TOSS", "evt-1", "PAYMENT_STATUS_CHANGED",
                    "{\"status\":\"DONE\"}", T0));
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("정합성 점검 로그가 대상일과 건수와 함께 저장된다")
    void persistsReconciliationLog() {
        ReconciliationLog log = reconciliationLogRepository.save(ReconciliationLog.record(
                LocalDate.of(2026, 11, 25), 128, 2, 1, "PG 정산 2건 불일치, 1건 자동 보정", T0));
        em.flush();
        em.clear();

        ReconciliationLog found = reconciliationLogRepository.findById(log.getId()).orElseThrow();
        assertThat(found.hasMismatch()).isTrue();
        assertThat(found.unresolvedCount()).isEqualTo(1);
        assertThat(reconciliationLogRepository.findByTargetDateOrderByExecutedAtDesc(LocalDate.of(2026, 11, 25)))
                .hasSize(1);
    }
}
