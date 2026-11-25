package com.subflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subflow.common.error.BusinessException;
import com.subflow.common.error.EntityNotFoundException;
import com.subflow.domain.audit.ActorType;
import com.subflow.domain.billingkey.BillingKey;
import com.subflow.domain.billingkey.BillingKeyRepository;
import com.subflow.domain.customer.Customer;
import com.subflow.domain.customer.CustomerRepository;
import com.subflow.domain.plan.BillingCalendar;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.plan.Plan;
import com.subflow.domain.plan.PlanRepository;
import com.subflow.domain.subscription.ChangeType;
import com.subflow.domain.subscription.EffectiveMode;
import com.subflow.domain.subscription.Subscription;
import com.subflow.domain.subscription.SubscriptionChangeHistory;
import com.subflow.domain.subscription.SubscriptionChangeHistoryRepository;
import com.subflow.domain.subscription.SubscriptionRepository;
import com.subflow.outbox.OutboxEventTypes;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구독 생애주기 서비스 — 생성·주기/플랜/수량/결제일 변경·해지·복구.
 *
 * <p>중요한 설계 원칙: <b>플랜·주기를 바꿔도 구독을 해지하고 새로 만들지 않는다.</b>
 * 같은 구독 안에서 누적 회차({@code billingCycleCount})와 결제 이력을 유지하고,
 * 모든 변경은 {@code subscription_change_histories}(before/after 스냅샷)와 {@code audit_logs}에 남긴다.
 */
@Service
public class SubscriptionLifecycleService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionChangeHistoryRepository changeHistoryRepository;
    private final CustomerRepository customerRepository;
    private final PlanRepository planRepository;
    private final BillingKeyRepository billingKeyRepository;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    public SubscriptionLifecycleService(SubscriptionRepository subscriptionRepository,
                                        SubscriptionChangeHistoryRepository changeHistoryRepository,
                                        CustomerRepository customerRepository,
                                        PlanRepository planRepository,
                                        BillingKeyRepository billingKeyRepository,
                                        AuditService auditService,
                                        OutboxService outboxService,
                                        ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.changeHistoryRepository = changeHistoryRepository;
        this.customerRepository = customerRepository;
        this.planRepository = planRepository;
        this.billingKeyRepository = billingKeyRepository;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 생성 / 조회
    // ------------------------------------------------------------------

    @Transactional
    public Subscription create(CreateSubscriptionCommand command, ActorType actorType, String actorId, Instant now) {
        Objects.requireNonNull(command, "command");
        Customer customer = customerRepository.findById(command.customerId())
                .orElseThrow(() -> new EntityNotFoundException("고객", command.customerId()));
        Plan plan = planRepository.findById(command.planId())
                .orElseThrow(() -> new EntityNotFoundException("플랜", command.planId()));

        // 단종(active=false) 플랜으로는 신규 구독을 만들 수 없다.
        if (!plan.isActive()) {
            throw BusinessException.badRequest("PLAN_INACTIVE",
                    "단종된 플랜으로는 새 구독을 만들 수 없습니다: " + plan.getCode());
        }

        BillingKey billingKey = null;
        if (command.billingKeyId() != null) {
            billingKey = billingKeyRepository.findById(command.billingKeyId())
                    .orElseThrow(() -> new EntityNotFoundException("빌링키", command.billingKeyId()));
            if (!billingKey.getCustomer().getId().equals(customer.getId())) {
                throw BusinessException.badRequest("BILLING_KEY_MISMATCH",
                        "다른 고객의 빌링키는 사용할 수 없습니다.");
            }
        }

        Subscription subscription = Subscription.start(
                customer, plan, billingKey, plan.getProject(), command.resolvedQuantity(), now);
        subscriptionRepository.save(subscription);

        auditService.recordSubscription(actorType, actorId, subscription.getPublicId(), "SUBSCRIPTION_CREATED",
                Map.of("planCode", plan.getCode(), "quantity", subscription.getQuantity(),
                        "billingCycle", subscription.effectiveBillingCycle().name()));
        outboxService.enqueueForProject(projectIdOf(subscription), "Subscription",
                String.valueOf(subscription.getId()), OutboxEventTypes.SUBSCRIPTION_CREATED,
                basePayload(subscription));

        return subscription;
    }

    @Transactional(readOnly = true)
    public Subscription get(Long subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new EntityNotFoundException("구독", subscriptionId));
    }

    @Transactional(readOnly = true)
    public List<Subscription> findByCustomer(Long customerId) {
        return subscriptionRepository.findByCustomerId(customerId);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionChangeHistory> histories(Long subscriptionId) {
        return changeHistoryRepository.findBySubscriptionIdOrderByCreatedAtDescIdDesc(subscriptionId);
    }

    // ------------------------------------------------------------------
    // 주기 변경 (+ 기준일 재계산)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public CycleChangePreview previewCycleChange(Long subscriptionId, BillingCycle newCycle,
                                                 EffectiveMode mode, Instant now) {
        Subscription subscription = get(subscriptionId);
        Objects.requireNonNull(newCycle, "newCycle");
        Objects.requireNonNull(now, "now");
        EffectiveMode effectiveMode = mode == null ? EffectiveMode.NEXT_CYCLE : mode;
        Integer newAnchorDay = newCycle.anchorDayFor(now);
        Instant preview = BillingCalendar.previewAfterCycleChange(
                effectiveMode.isImmediate(), now, subscription.getCurrentPeriodEnd(),
                newCycle, newAnchorDay);
        return new CycleChangePreview(subscriptionId,
                subscription.effectiveBillingCycle().name(), newCycle.name(),
                subscription.getAnchorDay(), newAnchorDay,
                subscription.getNextBillingAt(), preview, effectiveMode.isImmediate());
    }

    /**
     * 주기를 바꾸고 기준일(anchor day)을 명시적으로 다시 계산한다.
     *
     * <p>주 단위(WEEKLY/BIWEEKLY/FOUR_WEEKLY) ↔ 월 단위(MONTHLY/QUARTERLY/YEARLY)를 오가면
     * 기준일이 사라지거나 새로 생긴다. 변경은 이력에 before/after 스냅샷으로 남는다.
     */
    @Transactional
    public Subscription changeCycle(Long subscriptionId, BillingCycle newCycle, EffectiveMode mode,
                                    String reason, ActorType actorType, String actorId, Instant now) {
        Subscription subscription = lockForChange(subscriptionId);
        requireChangeable(subscription, "주기");
        EffectiveMode effectiveMode = mode == null ? EffectiveMode.NEXT_CYCLE : mode;

        String before = snapshotJson(subscription);
        Integer beforeAnchor = subscription.getAnchorDay();
        String beforeCycle = subscription.effectiveBillingCycle().name();
        subscription.changeCycle(newCycle, effectiveMode, now);
        String after = snapshotJson(subscription);

        saveHistory(subscription, ChangeType.CYCLE_CHANGE, before, after, effectiveMode, reason, actorType, actorId);
        auditService.recordSubscription(actorType, actorId, subscription.getPublicId(),
                "SUBSCRIPTION_CYCLE_CHANGED", Map.of(
                        "from", beforeCycle, "to", newCycle.name(),
                        "anchorDayBefore", beforeAnchor == null ? "null" : beforeAnchor,
                        "anchorDayAfter", subscription.getAnchorDay() == null ? "null" : subscription.getAnchorDay(),
                        "effectiveMode", effectiveMode.name(),
                        "nextBillingAt", String.valueOf(subscription.getNextBillingAt())));
        outboxService.enqueueForProject(projectIdOf(subscription), "Subscription",
                String.valueOf(subscription.getId()), OutboxEventTypes.SUBSCRIPTION_CYCLE_CHANGED,
                changePayload(subscription, Map.of("previousCycle", beforeCycle)));
        return subscription;
    }

    // ------------------------------------------------------------------
    // 플랜 변경 / 수량 변경 / 결제일 변경
    // ------------------------------------------------------------------

    /**
     * 플랜을 바꾼다. 구독을 해지/재생성하지 않으므로 누적 회차와 결제 이력이 유지된다.
     */
    @Transactional
    public Subscription changePlan(Long subscriptionId, Long newPlanId, EffectiveMode mode,
                                   String reason, ActorType actorType, String actorId, Instant now) {
        Subscription subscription = lockForChange(subscriptionId);
        requireChangeable(subscription, "플랜");
        Plan newPlan = planRepository.findById(newPlanId)
                .orElseThrow(() -> new EntityNotFoundException("플랜", newPlanId));
        if (!newPlan.isActive()) {
            throw BusinessException.badRequest("PLAN_INACTIVE",
                    "단종된 플랜으로는 변경할 수 없습니다: " + newPlan.getCode());
        }
        EffectiveMode effectiveMode = mode == null ? EffectiveMode.NEXT_CYCLE : mode;

        String before = snapshotJson(subscription);
        Long beforePlanId = subscription.getPlan().getId();
        String beforePlanCode = subscription.getPlan().getCode();
        int beforeCount = subscription.getBillingCycleCount();
        subscription.changePlan(newPlan, effectiveMode, now);
        String after = snapshotJson(subscription);

        saveHistory(subscription, ChangeType.PLAN_CHANGE, before, after, effectiveMode, reason, actorType, actorId);
        auditService.recordSubscription(actorType, actorId, subscription.getPublicId(),
                "SUBSCRIPTION_PLAN_CHANGED", Map.of(
                        "fromPlanId", beforePlanId,
                        "fromPlanCode", beforePlanCode,
                        "toPlanId", newPlan.getId(),
                        "toPlanCode", newPlan.getCode(),
                        "billingCycleCountBefore", beforeCount,
                        "billingCycleCountAfter", subscription.getBillingCycleCount(),
                        "effectiveMode", effectiveMode.name()));
        outboxService.enqueueForProject(projectIdOf(subscription), "Subscription",
                String.valueOf(subscription.getId()), OutboxEventTypes.SUBSCRIPTION_PLAN_CHANGED,
                changePayload(subscription, Map.of("previousPlanCode", beforePlanCode)));
        return subscription;
    }

    /** 수량(좌석) 변경. 다음 결제 금액이 플랜 단가 × 새 수량으로 계산된다. */
    @Transactional
    public Subscription changeQuantity(Long subscriptionId, int quantity, EffectiveMode mode,
                                       String reason, ActorType actorType, String actorId) {
        Subscription subscription = lockForChange(subscriptionId);
        requireChangeable(subscription, "수량");
        EffectiveMode effectiveMode = mode == null ? EffectiveMode.NEXT_CYCLE : mode;

        String before = snapshotJson(subscription);
        int beforeQuantity = subscription.getQuantity();
        subscription.changeQuantity(quantity);
        String after = snapshotJson(subscription);

        saveHistory(subscription, ChangeType.QUANTITY_CHANGE, before, after, effectiveMode, reason, actorType, actorId);
        auditService.recordSubscription(actorType, actorId, subscription.getPublicId(),
                "SUBSCRIPTION_QUANTITY_CHANGED", Map.of(
                        "from", beforeQuantity, "to", quantity,
                        "amountAfter", subscription.nextBillingAmount()));
        outboxService.enqueueForProject(projectIdOf(subscription), "Subscription",
                String.valueOf(subscription.getId()), OutboxEventTypes.SUBSCRIPTION_QUANTITY_CHANGED,
                changePayload(subscription, Map.of("previousQuantity", beforeQuantity)));
        return subscription;
    }

    /** 결제일(기준일) 변경. 월 단위 주기에서만 가능하다. */
    @Transactional
    public Subscription changeBillingDate(Long subscriptionId, int anchorDay, EffectiveMode mode,
                                          String reason, ActorType actorType, String actorId, Instant now) {
        Subscription subscription = lockForChange(subscriptionId);
        requireChangeable(subscription, "결제일");
        EffectiveMode effectiveMode = mode == null ? EffectiveMode.IMMEDIATE : mode;

        String before = snapshotJson(subscription);
        Integer beforeAnchor = subscription.getAnchorDay();
        subscription.changeAnchorDay(anchorDay, now);
        String after = snapshotJson(subscription);

        saveHistory(subscription, ChangeType.BILLING_DATE_CHANGE, before, after, effectiveMode,
                reason, actorType, actorId);
        auditService.recordSubscription(actorType, actorId, subscription.getPublicId(),
                "SUBSCRIPTION_BILLING_DATE_CHANGED", Map.of(
                        "from", beforeAnchor == null ? "null" : beforeAnchor,
                        "to", anchorDay,
                        "nextBillingAt", String.valueOf(subscription.getNextBillingAt())));
        outboxService.enqueueForProject(projectIdOf(subscription), "Subscription",
                String.valueOf(subscription.getId()), OutboxEventTypes.SUBSCRIPTION_BILLING_DATE_CHANGED,
                changePayload(subscription, Map.of("previousAnchorDay", beforeAnchor == null ? -1 : beforeAnchor)));
        return subscription;
    }

    // ------------------------------------------------------------------
    // 해지 / 복구
    // ------------------------------------------------------------------

    /**
     * 해지(사유 포함).
     *
     * @param immediate true 면 즉시 해지(CANCELED), false 면 기간 종료 해지 예약
     */
    @Transactional
    public Subscription cancel(Long subscriptionId, String reason, boolean immediate,
                               ActorType actorType, String actorId, Instant now) {
        Subscription subscription = lockForChange(subscriptionId);
        if (subscription.isTerminal()) {
            return subscription;
        }
        String before = snapshotJson(subscription);
        subscription.cancel(now, reason, immediate);
        String after = snapshotJson(subscription);

        EffectiveMode mode = immediate ? EffectiveMode.IMMEDIATE : EffectiveMode.NEXT_CYCLE;
        saveHistory(subscription, ChangeType.CANCEL, before, after, mode, reason, actorType, actorId);
        auditService.recordSubscription(actorType, actorId, subscription.getPublicId(),
                immediate ? "SUBSCRIPTION_CANCELED" : "SUBSCRIPTION_CANCEL_SCHEDULED",
                Map.of("reason", reason == null ? "" : reason,
                        "status", subscription.getStatus().name(),
                        "cancelAtPeriodEnd", subscription.isCancelAtPeriodEnd()));
        if (immediate) {
            outboxService.enqueueForProject(projectIdOf(subscription), "Subscription",
                    String.valueOf(subscription.getId()), OutboxEventTypes.SUBSCRIPTION_CANCELED,
                    changePayload(subscription, Map.of("reason", reason == null ? "" : reason,
                            "mode", "IMMEDIATE")));
        }
        return subscription;
    }

    /** 관리자 복구. 해지(CANCELED)된 구독을 지금부터 새 기간으로 되살린다. */
    @Transactional
    public Subscription restore(Long subscriptionId, ActorType actorType, String actorId, Instant now) {
        Subscription subscription = lockForChange(subscriptionId);
        String before = snapshotJson(subscription);
        subscription.restore(now);
        String after = snapshotJson(subscription);

        saveHistory(subscription, ChangeType.RESTORE, before, after, EffectiveMode.IMMEDIATE,
                "관리자 복구", actorType, actorId);
        auditService.recordSubscription(actorType, actorId, subscription.getPublicId(),
                "SUBSCRIPTION_RESTORED", Map.of("nextBillingAt", String.valueOf(subscription.getNextBillingAt())));
        outboxService.enqueueForProject(projectIdOf(subscription), "Subscription",
                String.valueOf(subscription.getId()), OutboxEventTypes.SUBSCRIPTION_RESTORED,
                changePayload(subscription, Map.of()));
        return subscription;
    }

    /**
     * 기간 종료 해지 확정 배치. 기간이 끝난 해지 예약 구독을 CANCELED 로 확정한다.
     */
    @Transactional
    public List<Subscription> applyPendingCancellations(Instant now) {
        List<Subscription> targets = subscriptionRepository.findExpiredByCancellation(now);
        for (Subscription subscription : targets) {
            if (!subscription.applyPendingCancellation(now)) {
                continue;
            }
            auditService.recordSubscription(ActorType.SYSTEM, null, subscription.getPublicId(),
                    "SUBSCRIPTION_CANCELED", Map.of("mode", "PERIOD_END",
                            "reason", subscription.getCancelReason() == null ? "" : subscription.getCancelReason()));
            outboxService.enqueueForProject(projectIdOf(subscription), "Subscription",
                    String.valueOf(subscription.getId()), OutboxEventTypes.SUBSCRIPTION_CANCELED,
                    changePayload(subscription, Map.of("mode", "PERIOD_END")));
        }
        return targets;
    }

    // ------------------------------------------------------------------
    // 내부
    // ------------------------------------------------------------------

    private Subscription lockForChange(Long subscriptionId) {
        return subscriptionRepository.findByIdForUpdate(subscriptionId)
                .orElseThrow(() -> new EntityNotFoundException("구독", subscriptionId));
    }

    private void requireChangeable(Subscription subscription, String what) {
        if (!subscription.isChangeable()) {
            throw BusinessException.conflict("SUBSCRIPTION_NOT_CHANGEABLE",
                    "이용 중(또는 체험 중)인 구독만 " + what + "을(를) 바꿀 수 있습니다. 현재 상태: "
                            + subscription.getStatus());
        }
    }

    private void saveHistory(Subscription subscription, ChangeType changeType, String before, String after,
                             EffectiveMode mode, String reason, ActorType actorType, String actorId) {
        changeHistoryRepository.save(SubscriptionChangeHistory.record(
                subscription, changeType, before, after, mode, reason, actorType, actorId));
    }

    private String snapshotJson(Subscription subscription) {
        try {
            return objectMapper.writeValueAsString(SubscriptionSnapshot.of(subscription));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("구독 스냅샷 직렬화에 실패했습니다.", e);
        }
    }

    private Map<String, Object> changePayload(Subscription subscription, Map<String, Object> extra) {
        Map<String, Object> payload = basePayload(subscription);
        payload.putAll(extra);
        return payload;
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

    private Long projectIdOf(Subscription subscription) {
        if (subscription.getProject() != null) {
            return subscription.getProject().getId();
        }
        if (subscription.getPlan().getProject() != null) {
            return subscription.getPlan().getProject().getId();
        }
        return null;
    }
}
