package com.subflow.service;

import com.subflow.common.error.DuplicateBillingAttemptException;
import com.subflow.common.error.EntityNotFoundException;
import com.subflow.config.SubFlowProperties;
import com.subflow.domain.audit.ActorType;
import com.subflow.domain.billingkey.BillingKey;
import com.subflow.domain.payment.Payment;
import com.subflow.domain.payment.PaymentAttempt;
import com.subflow.domain.payment.PaymentAttemptRepository;
import com.subflow.domain.payment.PaymentRepository;
import com.subflow.domain.payment.PaymentStatus;
import com.subflow.domain.subscription.Subscription;
import com.subflow.domain.subscription.SubscriptionRepository;
import com.subflow.outbox.OutboxEventTypes;
import com.subflow.pg.ChargeRequest;
import com.subflow.pg.ChargeResult;
import com.subflow.pg.PgClient;
import com.subflow.pg.PgException;
import com.subflow.pg.PgTimeoutException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정기결제 1건의 실제 처리기(트랜잭션 경계 안쪽).
 *
 * <p>방어선 순서:
 * <ol>
 *   <li>분산 락(구독 단위) — {@link BillingExecutionService} 가 잡는다.</li>
 *   <li>비관적 락({@code findByIdForUpdate}) — 행 단위 직렬화.</li>
 *   <li>중복 결제 가드 — {@code last_billed_at} 기준 당일 차단 + 보호 윈도우.</li>
 *   <li>멱등키 — {@code payments.idempotency_key} 유니크. 같은 요청은 새 결제를 만들지 않는다.</li>
 * </ol>
 *
 * <p>PG 호출은 트랜잭션 안에서 이뤄진다. 응답을 받은 뒤의 원장·구독 갱신은 같은 커밋으로 묶여,
 * "결제는 됐는데 구독이 안 열리는" 어긋남을 만들지 않는다. 응답을 못 받으면(타임아웃) 실패로 단정하지 않고
 * PENDING(미확정)으로 남겨 정합성 점검이 확정하게 한다.
 */
@Service
public class BillingChargeProcessor {

    private static final Logger log = LoggerFactory.getLogger(BillingChargeProcessor.class);
    private static final String AGGREGATE_SUBSCRIPTION = "Subscription";
    private static final String AGGREGATE_PAYMENT = "Payment";

    private final SubscriptionRepository subscriptionRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PgClient pgClient;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final SubFlowProperties properties;

    public BillingChargeProcessor(SubscriptionRepository subscriptionRepository,
                                  PaymentRepository paymentRepository,
                                  PaymentAttemptRepository paymentAttemptRepository,
                                  PgClient pgClient,
                                  OutboxService outboxService,
                                  AuditService auditService,
                                  SubFlowProperties properties) {
        this.subscriptionRepository = subscriptionRepository;
        this.paymentRepository = paymentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.pgClient = pgClient;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.properties = properties;
    }

    @Transactional
    public BillingOutcome charge(Long subscriptionId, BillingReason reason, Instant now) {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(now, "now");

        Subscription subscription = subscriptionRepository.findByIdForUpdate(subscriptionId)
                .orElseThrow(() -> new EntityNotFoundException("구독", subscriptionId));
        BigDecimal amount = subscription.nextBillingAmount();

        if (!subscription.getStatus().isBillable()) {
            return BillingOutcome.skipped(subscriptionId, BillingResultStatus.SKIPPED_NOT_BILLABLE,
                    "청구 가능한 상태가 아닙니다: " + subscription.getStatus(),
                    amount, subscription.getBillingCycleCount(), subscription.getNextBillingAt());
        }
        if (subscription.isCancelAtPeriodEnd()) {
            return BillingOutcome.skipped(subscriptionId, BillingResultStatus.SKIPPED_CANCEL_SCHEDULED,
                    "기간 종료 해지가 예약된 구독입니다.", amount,
                    subscription.getBillingCycleCount(), subscription.getNextBillingAt());
        }
        if (!reason.isManual() && !subscription.isDue(now)) {
            return BillingOutcome.skipped(subscriptionId, BillingResultStatus.SKIPPED_NOT_DUE,
                    "아직 청구일이 아닙니다: nextBillingAt=" + subscription.getNextBillingAt(),
                    amount, subscription.getBillingCycleCount(), subscription.getNextBillingAt());
        }
        if (subscription.isWithinDuplicateGuard(now, duplicateGuardWindow())) {
            if (reason.isManual()) {
                throw new DuplicateBillingAttemptException(
                        "이미 결제된 구독입니다. 마지막 결제: " + subscription.getLastBilledAt()
                                + ", 보호 윈도우: " + duplicateGuardWindow());
            }
            return BillingOutcome.skipped(subscriptionId, BillingResultStatus.SKIPPED_DUPLICATE,
                    "보호 윈도우 안에서 이미 결제되었습니다: " + subscription.getLastBilledAt(),
                    amount, subscription.getBillingCycleCount(), subscription.getNextBillingAt());
        }

        BillingKey billingKey = subscription.getBillingKey();
        if (billingKey == null) {
            return BillingOutcome.skipped(subscriptionId, BillingResultStatus.SKIPPED_NO_BILLING_KEY,
                    "빌링키가 없어 청구할 수 없습니다.", amount,
                    subscription.getBillingCycleCount(), subscription.getNextBillingAt());
        }

        String idempotencyKey = idempotencyKey(subscription, reason, now);
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(subscription, existing.get());
        }

        Payment payment = paymentRepository.save(Payment.request(
                subscription, idempotencyKey, amount, subscription.currency(), billingKey.getPgProvider()));
        Long projectId = projectIdOf(subscription);
        int attemptNo = nextAttemptNo(payment);
        String orderId = "sub_" + subscription.getId() + "_c" + subscription.nextBillingCycleNumber();

        ChargeRequest request = new ChargeRequest(
                billingKey.getBillingKeyToken(),
                amount,
                subscription.currency(),
                orderId,
                idempotencyKey,
                Map.of("subscriptionId", String.valueOf(subscription.getId()),
                        "billingCycle", subscription.effectiveBillingCycle().name()));

        ChargeResult result;
        try {
            result = pgClient.chargeWithBillingKey(request);
        } catch (PgTimeoutException timeout) {
            return handleTimeout(subscription, payment, projectId, attemptNo, timeout, now);
        } catch (PgException pgError) {
            return handleDecline(subscription, payment, projectId, attemptNo,
                    pgError.getCode(), pgError.getMessage(), null, now);
        }

        if (result.approved()) {
            return handleApproved(subscription, payment, projectId, attemptNo, result, now);
        }
        return handleDecline(subscription, payment, projectId, attemptNo,
                result.failureCode(), result.failureReason(), result.pgTxId(), now);
    }

    // ------------------------------------------------------------------
    // 결과 처리
    // ------------------------------------------------------------------

    private BillingOutcome handleApproved(Subscription subscription, Payment payment, Long projectId,
                                          int attemptNo, ChargeResult result, Instant now) {
        payment.markSucceeded(result.pgTxId(), now);
        paymentAttemptRepository.save(PaymentAttempt.success(payment, attemptNo, now));

        Map<String, Object> payload = basePayload(subscription);
        payload.put("paymentId", payment.getId());
        payload.put("paymentPublicId", payment.getPublicId().toString());
        payload.put("amount", payment.getAmount());
        payload.put("currency", payment.getCurrency());
        payload.put("pgTxId", payment.getPgTxId());
        payload.put("paidAt", now);

        if (!subscription.getStatus().isBillable()) {
            // PG 승인 도중 해지된 드문 경우: 결제는 성공으로 남기고 구독은 건드리지 않는다.
            payload.put("note", "청구 중 구독이 청구 불가 상태가 되어 갱신하지 않음(수동 확인 필요)");
            outboxService.enqueueForProject(projectId, AGGREGATE_PAYMENT,
                    String.valueOf(payment.getId()), OutboxEventTypes.PAYMENT_SUCCEEDED, payload);
            auditService.recordSubscription(ActorType.SYSTEM, null, subscription.getPublicId(),
                    "BILLING_SUCCEEDED_WITHOUT_RENEWAL", Map.of("paymentId", payment.getId()));
            log.warn("결제는 승인됐지만 구독이 청구 불가 상태입니다. subscription={}, status={}",
                    subscription.getId(), subscription.getStatus());
            return new BillingOutcome(subscription.getId(), payment.getId(), payment.getPublicId().toString(),
                    BillingResultStatus.SUCCEEDED, "승인됨(구독 상태 변경 없음)",
                    payment.getAmount(), payment.getPgTxId(),
                    subscription.getBillingCycleCount(), subscription.getNextBillingAt());
        }

        subscription.recordBillingSuccess(now);
        payload.put("billingCycleCount", subscription.getBillingCycleCount());
        payload.put("nextBillingAt", subscription.getNextBillingAt());

        outboxService.enqueueForProject(projectId, AGGREGATE_PAYMENT,
                String.valueOf(payment.getId()), OutboxEventTypes.PAYMENT_SUCCEEDED, payload);
        outboxService.enqueueForProject(projectId, AGGREGATE_SUBSCRIPTION,
                String.valueOf(subscription.getId()), OutboxEventTypes.SUBSCRIPTION_RENEWED,
                basePayload(subscription));

        return new BillingOutcome(subscription.getId(), payment.getId(), payment.getPublicId().toString(),
                BillingResultStatus.SUCCEEDED, "결제 성공(" + subscription.getBillingCycleCount() + "회차)",
                payment.getAmount(), payment.getPgTxId(),
                subscription.getBillingCycleCount(), subscription.getNextBillingAt());
    }

    private BillingOutcome handleTimeout(Subscription subscription, Payment payment, Long projectId,
                                         int attemptNo, PgTimeoutException timeout, Instant now) {
        payment.markUncertain("PG_TIMEOUT", truncate(timeout.getMessage(), 500), timeout.getPgTxId());
        paymentAttemptRepository.save(
                PaymentAttempt.failure(payment, attemptNo, "PG_TIMEOUT: " + truncate(timeout.getMessage(), 400), now));

        Map<String, Object> payload = basePayload(subscription);
        payload.put("paymentId", payment.getId());
        payload.put("paymentPublicId", payment.getPublicId().toString());
        payload.put("amount", payment.getAmount());
        payload.put("orderId", timeout.getOrderId());
        payload.put("pgTxId", timeout.getPgTxId());
        payload.put("note", "PG 응답 타임아웃 — 정합성 점검이 승인 여부를 확정합니다");
        outboxService.enqueueForProject(projectId, AGGREGATE_PAYMENT,
                String.valueOf(payment.getId()), OutboxEventTypes.PAYMENT_PENDING_CONFIRMATION, payload);

        auditService.recordSubscription(ActorType.SYSTEM, null, subscription.getPublicId(),
                "BILLING_RESULT_UNCERTAIN", Map.of("paymentId", payment.getId(), "orderId", timeout.getOrderId()));

        // 구독은 그대로 둔다. Dunning 횟수도 올리지 않는다(승인됐을 수 있으므로 재청구 금지).
        return new BillingOutcome(subscription.getId(), payment.getId(), payment.getPublicId().toString(),
                BillingResultStatus.PENDING_CONFIRMATION,
                "PG 응답 타임아웃 — 승인 여부 미확정(정합성 점검 대상)", payment.getAmount(), timeout.getPgTxId(),
                subscription.getBillingCycleCount(), subscription.getNextBillingAt());
    }

    private BillingOutcome handleDecline(Subscription subscription, Payment payment, Long projectId,
                                         int attemptNo, String code, String reason, String pgTxId, Instant now) {
        payment.markFailed(code == null ? "PG_DECLINED" : code, truncate(reason, 500));
        paymentAttemptRepository.save(PaymentAttempt.failure(payment, attemptNo,
                (code == null ? "PG_DECLINED" : code) + ": " + truncate(reason, 400), now));

        Map<String, Object> payload = basePayload(subscription);
        payload.put("paymentId", payment.getId());
        payload.put("paymentPublicId", payment.getPublicId().toString());
        payload.put("amount", payment.getAmount());
        payload.put("failureCode", code);
        payload.put("failureReason", reason);
        payload.put("pgTxId", pgTxId);
        outboxService.enqueueForProject(projectId, AGGREGATE_PAYMENT,
                String.valueOf(payment.getId()), OutboxEventTypes.PAYMENT_FAILED, payload);

        // 미결제 상태에서 해지된 구독은 Dunning 재시도 대상이 아니다(추가 결제 방지).
        if (!subscription.getStatus().isBillable()) {
            auditService.recordSubscription(ActorType.SYSTEM, null, subscription.getPublicId(),
                    "DUNNING_EXCLUDED_CANCELED",
                    Map.of("paymentId", payment.getId(), "status", subscription.getStatus().name()));
            return new BillingOutcome(subscription.getId(), payment.getId(), payment.getPublicId().toString(),
                    BillingResultStatus.FAILED,
                    "결제 실패(" + code + ") — 해지된 구독이라 Dunning 재시도를 하지 않습니다",
                    payment.getAmount(), pgTxId,
                    subscription.getBillingCycleCount(), subscription.getNextBillingAt());
        }

        boolean expired = subscription.registerPaymentFailure(
                properties.dunning().maxRetries(), now.plus(properties.dunning().retryInterval()));

        Map<String, Object> subscriptionPayload = basePayload(subscription);
        subscriptionPayload.put("failureCode", code);
        subscriptionPayload.put("retryCount", subscription.getRetryCount());
        outboxService.enqueueForProject(projectId, AGGREGATE_SUBSCRIPTION,
                String.valueOf(subscription.getId()),
                expired ? OutboxEventTypes.SUBSCRIPTION_EXPIRED : OutboxEventTypes.SUBSCRIPTION_PAST_DUE,
                subscriptionPayload);

        auditService.recordSubscription(ActorType.SYSTEM, null, subscription.getPublicId(),
                expired ? "SUBSCRIPTION_EXPIRED_DUNNING_EXHAUSTED" : "PAYMENT_FAILED",
                Map.of("paymentId", payment.getId(), "failureCode", code == null ? "" : code,
                        "retryCount", subscription.getRetryCount()));

        return new BillingOutcome(subscription.getId(), payment.getId(), payment.getPublicId().toString(),
                expired ? BillingResultStatus.EXPIRED : BillingResultStatus.FAILED,
                "결제 실패(" + code + "), 재시도 " + subscription.getRetryCount() + "/"
                        + properties.dunning().maxRetries() + (expired ? " — 구독 만료" : ""),
                payment.getAmount(), pgTxId,
                subscription.getBillingCycleCount(), subscription.getNextBillingAt());
    }

    private BillingOutcome replay(Subscription subscription, Payment payment) {
        BillingResultStatus status = payment.getStatus() == PaymentStatus.PENDING
                ? BillingResultStatus.PENDING_CONFIRMATION
                : BillingResultStatus.REPLAYED;
        return new BillingOutcome(subscription.getId(), payment.getId(), payment.getPublicId().toString(),
                status,
                "이미 처리된 결제 요청입니다(멱등키 재사용): payment=" + payment.getStatus(),
                payment.getAmount(), payment.getPgTxId(),
                subscription.getBillingCycleCount(), subscription.getNextBillingAt());
    }

    // ------------------------------------------------------------------
    // 내부
    // ------------------------------------------------------------------

    /**
     * 멱등키. 같은 회차·같은 재시도 번호는 같은 키가 되어 중복 승인을 막고,
     * Dunning 재시도는 재시도 번호가 올라가 새 결제로 시도된다.
     */
    private String idempotencyKey(Subscription subscription, BillingReason reason, Instant now) {
        if (reason.isManual()) {
            return "sub-" + subscription.getId() + "-m-" + now.toEpochMilli();
        }
        return "sub-" + subscription.getId()
                + "-c" + subscription.nextBillingCycleNumber()
                + "-t" + (subscription.getRetryCount() + 1);
    }

    private Duration duplicateGuardWindow() {
        return properties.billing().duplicateGuardWindow();
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

    private Map<String, Object> basePayload(Subscription subscription) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subscriptionId", subscription.getId());
        payload.put("subscriptionPublicId", subscription.getPublicId().toString());
        payload.put("customerId", subscription.getCustomer().getId());
        payload.put("planCode", subscription.getPlan().getCode());
        payload.put("status", subscription.getStatus().name());
        payload.put("billingCycle", subscription.effectiveBillingCycle().name());
        payload.put("quantity", subscription.getQuantity());
        payload.put("billingCycleCount", subscription.getBillingCycleCount());
        payload.put("nextBillingAt", subscription.getNextBillingAt());
        return payload;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
