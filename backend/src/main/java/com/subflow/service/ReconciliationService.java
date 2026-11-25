package com.subflow.service;

import com.subflow.domain.audit.ActorType;
import com.subflow.domain.payment.Payment;
import com.subflow.domain.payment.PaymentAttempt;
import com.subflow.domain.payment.PaymentAttemptRepository;
import com.subflow.domain.payment.PaymentRepository;
import com.subflow.domain.payment.PaymentStatus;
import com.subflow.domain.reconciliation.ReconciliationLog;
import com.subflow.domain.reconciliation.ReconciliationLogRepository;
import com.subflow.domain.subscription.Subscription;
import com.subflow.domain.subscription.SubscriptionRepository;
import com.subflow.outbox.OutboxEventTypes;
import com.subflow.pg.PaymentStatusResult;
import com.subflow.pg.PgClient;
import com.subflow.pg.PgTransactionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정합성 점검 — PG 실제 상태와 내부 결제 원장을 대사한다.
 *
 * <p>찾는 것:
 * <ul>
 *   <li>PG 는 승인했는데 원장이 PENDING(타임아웃 등) → 승인으로 보정 + 구독 갱신</li>
 *   <li>PG 는 거절했는데 원장이 PENDING → 실패로 보정 + Dunning 반영</li>
 *   <li>PG 는 취소됐는데 원장이 SUCCEEDED → 취소로 보정 + 구독 즉시 해지</li>
 *   <li>PG 에 거래가 없는데 원장이 SUCCEEDED → 자동 보정하지 않고 불일치로 남긴다(수동 확인)</li>
 * </ul>
 * 결과는 {@code reconciliation_logs} 에 남기고, 자동 보정한 건수와 남은 불일치를 구분해 기록한다.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ReconciliationLogRepository reconciliationLogRepository;
    private final SubscriptionLifecycleService subscriptionLifecycleService;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final PgClient pgClient;

    public ReconciliationService(PaymentRepository paymentRepository,
                                 PaymentAttemptRepository paymentAttemptRepository,
                                 SubscriptionRepository subscriptionRepository,
                                 ReconciliationLogRepository reconciliationLogRepository,
                                 SubscriptionLifecycleService subscriptionLifecycleService,
                                 OutboxService outboxService,
                                 AuditService auditService,
                                 PgClient pgClient) {
        this.paymentRepository = paymentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.reconciliationLogRepository = reconciliationLogRepository;
        this.subscriptionLifecycleService = subscriptionLifecycleService;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.pgClient = pgClient;
    }

    @Transactional
    public ReconciliationResult run(Instant now) {
        List<Payment> targets = paymentRepository.findByStatusInOrderByIdAsc(
                List.of(PaymentStatus.PENDING, PaymentStatus.SUCCEEDED));

        int mismatch = 0;
        int fixed = 0;
        List<String> details = new ArrayList<>();

        for (Payment payment : targets) {
            PgTransactionStatus pgStatus = payment.getPgTxId() == null
                    ? PgTransactionStatus.UNKNOWN
                    : pgClient.fetchPaymentStatus(payment.getPgTxId()).status();
            if (pgStatus == PgTransactionStatus.UNKNOWN && payment.getPgTxId() == null) {
                mismatch++;
                details.add("payment=" + payment.getId() + " PG 거래 ID 없음(확인 필요)");
                continue;
            }

            if (payment.getStatus() == PaymentStatus.PENDING) {
                switch (pgStatus) {
                    case APPROVED -> {
                        fixPendingToSucceeded(payment, now);
                        mismatch++;
                        fixed++;
                        details.add("payment=" + payment.getId() + " PENDING→SUCCEEDED 보정(PG 승인)");
                    }
                    case DECLINED -> {
                        fixPendingToFailed(payment, now);
                        mismatch++;
                        fixed++;
                        details.add("payment=" + payment.getId() + " PENDING→FAILED 보정(PG 거절)");
                    }
                    case CANCELED -> {
                        payment.markCanceled("PG 취소 확인(정합성 보정)");
                        mismatch++;
                        fixed++;
                        details.add("payment=" + payment.getId() + " PENDING→CANCELED 보정(PG 취소)");
                    }
                    default -> {
                        mismatch++;
                        details.add("payment=" + payment.getId() + " PENDING 인데 PG 상태 미확정(" + pgStatus + ")");
                    }
                }
                continue;
            }

            // SUCCEEDED
            if (pgStatus == PgTransactionStatus.CANCELED) {
                fixSucceededToCanceled(payment, now);
                mismatch++;
                fixed++;
                details.add("payment=" + payment.getId() + " SUCCEEDED→CANCELED 보정 + 구독 해지");
            } else if (pgStatus != PgTransactionStatus.APPROVED) {
                mismatch++;
                details.add("payment=" + payment.getId() + " SUCCEEDED 인데 PG 상태 " + pgStatus + "(수동 확인)");
            }
        }

        String summary = mismatch == 0
                ? "불일치 없음(대사 " + targets.size() + "건)"
                : "대사 " + targets.size() + "건 중 " + mismatch + "건 불일치, " + fixed + "건 자동 보정";
        ReconciliationLog saved = reconciliationLogRepository.save(ReconciliationLog.record(
                LocalDate.ofInstant(now, ZoneOffset.UTC), targets.size(), mismatch, fixed,
                summary + (details.isEmpty() ? "" : " | " + String.join(" ; ", details)), now));
        log.info("정합성 점검 완료: {}", summary);

        return new ReconciliationResult(saved.getId(), saved.getTargetDate(), now,
                targets.size(), mismatch, fixed, List.copyOf(details));
    }

    private void fixPendingToSucceeded(Payment payment, Instant now) {
        payment.markSucceeded(payment.getPgTxId(), now);
        paymentAttemptRepository.save(PaymentAttempt.success(payment, nextAttemptNo(payment), now));

        Subscription subscription = subscriptionRepository
                .findByIdForUpdate(payment.getSubscription().getId()).orElse(null);
        if (subscription == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", payment.getId());
        payload.put("subscriptionId", subscription.getId());
        payload.put("amount", payment.getAmount());
        payload.put("pgTxId", payment.getPgTxId());
        payload.put("note", "정합성 점검으로 승인 확인");

        boolean renewed = false;
        if (subscription.getStatus().isBillable() && !subscription.isWithinDuplicateGuard(
                now, java.time.Duration.ZERO)) {
            subscription.recordBillingSuccess(now);
            renewed = true;
            payload.put("billingCycleCount", subscription.getBillingCycleCount());
            payload.put("nextBillingAt", subscription.getNextBillingAt());
        }
        outboxService.enqueueForProject(projectIdOf(subscription), "Payment",
                String.valueOf(payment.getId()), OutboxEventTypes.PAYMENT_SUCCEEDED, payload);
        if (renewed) {
            outboxService.enqueueForProject(projectIdOf(subscription), "Subscription",
                    String.valueOf(subscription.getId()), OutboxEventTypes.SUBSCRIPTION_RENEWED, payload);
        }
        auditService.recordSubscription(ActorType.SYSTEM, null, subscription.getPublicId(),
                "RECONCILIATION_FIXED_PENDING_TO_SUCCEEDED",
                Map.of("paymentId", payment.getId(), "renewed", renewed));
    }

    private void fixPendingToFailed(Payment payment, Instant now) {
        payment.markFailed("PG_DECLINED_AT_RECONCILIATION", "정합성 점검에서 PG 거절 확인");
        paymentAttemptRepository.save(PaymentAttempt.failure(payment, nextAttemptNo(payment),
                "정합성 점검: PG 거절 확인", now));

        Subscription subscription = subscriptionRepository
                .findByIdForUpdate(payment.getSubscription().getId()).orElse(null);
        if (subscription != null && subscription.getStatus().isBillable()) {
            subscription.registerPaymentFailure(30, now.plus(java.time.Duration.ofHours(24)));
            auditService.recordSubscription(ActorType.SYSTEM, null, subscription.getPublicId(),
                    "RECONCILIATION_FIXED_PENDING_TO_FAILED",
                    Map.of("paymentId", payment.getId(), "retryCount", subscription.getRetryCount()));
        }
    }

    private void fixSucceededToCanceled(Payment payment, Instant now) {
        payment.markCanceled("PG 취소 확인(정합성 보정)");
        Subscription subscription = payment.getSubscription();
        if (!subscription.isTerminal()) {
            subscriptionLifecycleService.cancel(subscription.getId(),
                    "PG 취소 확인(정합성 보정)", true, ActorType.SYSTEM, null, now);
        }
        auditService.recordSubscription(ActorType.SYSTEM, null, subscription.getPublicId(),
                "RECONCILIATION_FIXED_SUCCEEDED_TO_CANCELED",
                Map.of("paymentId", payment.getId(), "pgTxId", String.valueOf(payment.getPgTxId())));
    }

    private int nextAttemptNo(Payment payment) {
        return (int) paymentAttemptRepository.countByPaymentId(payment.getId()) + 1;
    }

    private Long projectIdOf(Subscription subscription) {
        if (subscription.getProject() != null) {
            return subscription.getProject().getId();
        }
        if (subscription.getPlan().getProject() != null) {
            return subscription.getPlan().getProject().getId();
        }
        return null;
    }

    /** PG 상태 조회 편의(테스트/운영 확인용). */
    @Transactional(readOnly = true)
    public PaymentStatusResult pgStatus(String pgTxId) {
        return pgClient.fetchPaymentStatus(pgTxId);
    }
}
