package com.subflow.domain.subscription;

import com.subflow.common.error.InvalidStateTransitionException;
import com.subflow.domain.billingkey.BillingKey;
import com.subflow.domain.customer.Customer;
import com.subflow.domain.plan.BillingCalendar;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.plan.Plan;
import com.subflow.domain.project.Project;
import com.subflow.domain.support.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구독 애그리거트.
 *
 * <p>상태 전이는 {@link SubscriptionStatus}의 규칙을 따르며, 외부에서 상태를 직접 대입할 수 없다.
 * 결제 실행 서비스는 이 엔티티의 도메인 메서드만 호출하고, 그 결과를 결제 원장({@code Payment})에 남긴다.
 *
 * <p>2단계에서 더한 것:
 * <ul>
 *   <li>{@code anchorDay}: 월 단위 주기의 기준일(1~31). 주기 변경 시 명시적으로 다시 계산한다.</li>
 *   <li>{@code billingCycleCount}: 누적 정상 결제 회차. 플랜을 바꿔도 유지된다.</li>
 *   <li>{@code lastBilledAt}: 당일/보호 윈도우 중복 결제 가드의 기준.</li>
 *   <li>{@code quantity}: 청구 금액 = 플랜 단가 × 수량.</li>
 *   <li>{@code billingCycle}: 플랜 주기 override. null 이면 플랜 주기를 따른다.</li>
 * </ul>
 *
 * <p>동시성: 낙관적 락({@link Version}) + 비관적 락({@code findByIdForUpdate}) + 구독 단위 분산 락.
 */
@Entity
@Table(name = "subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부(API·감사 로그)에 노출하는 식별자. */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    /** 결제에 사용할 빌링키. 체험 구독은 첫 결제 전까지 없을 수 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "billing_key_id")
    private BillingKey billingKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SubscriptionStatus status;

    /** 플랜 주기 override. null 이면 플랜 주기를 따른다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", length = 20)
    private BillingCycle billingCycle;

    /** 월 단위 주기의 청구 기준일(1~31). 주 단위 주기에서는 null. */
    @Column(name = "anchor_day")
    private Integer anchorDay;

    /** 누적 정상 결제 회차. 플랜 변경에도 유지된다. */
    @Column(name = "billing_cycle_count", nullable = false)
    private int billingCycleCount;

    /** 마지막 정상 결제 시각. 중복 결제 가드 기준. */
    @Column(name = "last_billed_at")
    private Instant lastBilledAt;

    @Column(name = "current_period_start", nullable = false)
    private Instant currentPeriodStart;

    @Column(name = "current_period_end", nullable = false)
    private Instant currentPeriodEnd;

    /** 다음 청구 예정 시각. 해지/만료 상태에서는 null. */
    @Column(name = "next_billing_at")
    private Instant nextBillingAt;

    /** 연체 후 실패한 결제 시도 횟수. 성공하면 0으로 돌아간다. */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    /** 기간 종료 시 해지 예약 여부. true여도 기간이 끝나기 전까지는 이용 가능하다. */
    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd;

    /** 해지 요청 시각. */
    @Column(name = "canceled_at")
    private Instant canceledAt;

    /** 해지 사유(고객/관리자 입력). */
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    /** 구독 수량(좌석 수 등). 청구 금액 = 플랜 단가 × 수량. */
    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    private Subscription(Customer customer, Plan plan, BillingKey billingKey, Project project,
                         int quantity, Instant now) {
        this.publicId = UUID.randomUUID();
        this.customer = Objects.requireNonNull(customer, "customer");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.billingKey = billingKey;
        this.project = project != null ? project : plan.getProject();
        this.quantity = validateQuantity(quantity);
        this.status = plan.hasTrial() ? SubscriptionStatus.TRIAL : SubscriptionStatus.ACTIVE;
        this.currentPeriodStart = now;
        this.currentPeriodEnd = plan.initialPeriodEnd(now);
        this.nextBillingAt = this.currentPeriodEnd;
        this.retryCount = 0;
        this.cancelAtPeriodEnd = false;
        this.billingCycleCount = 0;
        this.anchorDay = plan.getBillingCycle().anchorDayFor(now);
    }

    /**
     * 구독을 시작한다. 체험 플랜이면 TRIAL, 아니면 ACTIVE로 시작하며 첫 청구일은 주기 종료 시점이다.
     *
     * @throws IllegalArgumentException 체험 없는 플랜인데 빌링키가 없을 때
     */
    public static Subscription start(Customer customer, Plan plan, BillingKey billingKey, Instant now) {
        return start(customer, plan, billingKey, null, 1, now);
    }

    public static Subscription start(Customer customer, Plan plan, BillingKey billingKey,
                                     Project project, int quantity, Instant now) {
        Objects.requireNonNull(now, "now");
        if (!plan.hasTrial() && billingKey == null) {
            throw new IllegalArgumentException("체험 없는 플랜은 빌링키가 필요합니다: plan=" + plan.getCode());
        }
        if (billingKey != null) {
            billingKey.ensureUsable();
        }
        return new Subscription(customer, plan, billingKey, project, quantity, now);
    }

    // ------------------------------------------------------------------
    // 상태 전이
    // ------------------------------------------------------------------

    /** 상태 머신 규칙에 따라 전이한다. 허용되지 않는 전이는 예외다. */
    public void transitionTo(SubscriptionStatus target) {
        if (target == null) {
            throw new IllegalArgumentException("목표 상태가 없습니다.");
        }
        if (!status.canTransitionTo(target)) {
            throw InvalidStateTransitionException.of("구독", status, target);
        }
        this.status = target;
        if (target == SubscriptionStatus.CANCELED || target == SubscriptionStatus.EXPIRED) {
            this.nextBillingAt = null;
        }
    }

    /** 결제 실패를 연체로 반영한다. 재시도 시각을 다음 청구 시각으로 옮긴다. */
    public void markPastDue(Instant nextRetryAt) {
        if (!status.isBillable()) {
            throw new InvalidStateTransitionException("청구 가능한 상태가 아닙니다: " + status);
        }
        if (status != SubscriptionStatus.PAST_DUE) {
            transitionTo(SubscriptionStatus.PAST_DUE);
        }
        this.nextBillingAt = nextRetryAt;
    }

    /**
     * 결제 실패 1회를 기록한다.
     *
     * <p>재시도 횟수가 한도에 도달하면 구독을 만료시키고, 그 전에는 연체(PAST_DUE)로 두고
     * 다음 재시도 시각을 잡는다. 해지(CANCELED)된 구독에는 <b>호출하면 안 된다</b>(Dunning 제외).
     *
     * @param maxRetries  최대 재시도 횟수 (1 이상)
     * @param nextRetryAt 다음 재시도 시각
     * @return 이번 실패로 구독이 만료되었으면 true
     */
    public boolean registerPaymentFailure(int maxRetries, Instant nextRetryAt) {
        if (maxRetries < 1) {
            throw new IllegalArgumentException("최대 재시도 횟수는 1 이상이어야 합니다: " + maxRetries);
        }
        if (!status.isBillable()) {
            throw new InvalidStateTransitionException("청구 가능한 상태가 아닙니다: " + status);
        }
        this.retryCount++;
        if (this.retryCount >= maxRetries) {
            expire();
            return true;
        }
        markPastDue(nextRetryAt);
        return false;
    }

    /**
     * 결제 성공(정기 갱신 또는 연체 해소)을 반영하고 다음 주기를 연다.
     *
     * <p>해지 예약된 구독은 갱신하지 않고 만료로 확정한다.
     */
    public void renew(Instant paidAt) {
        Objects.requireNonNull(paidAt, "paidAt");
        if (!status.isBillable()) {
            throw new InvalidStateTransitionException("갱신할 수 없는 상태입니다: " + status);
        }
        if (cancelAtPeriodEnd) {
            expire();
            return;
        }
        if (status != SubscriptionStatus.ACTIVE) {
            transitionTo(SubscriptionStatus.ACTIVE);
        }
        this.retryCount = 0;
        this.currentPeriodStart = paidAt.isAfter(currentPeriodEnd) ? paidAt : currentPeriodEnd;
        this.currentPeriodEnd = effectiveBillingCycle().advance(this.currentPeriodStart);
        this.nextBillingAt = this.currentPeriodEnd;
    }

    /**
     * 정기결제 성공을 원장과 함께 반영한다.
     *
     * <p>{@link #renew(Instant)} 와 다른 점:
     * <ul>
     *   <li>다음 청구일을 기준일(anchor day)에 맞춘다.</li>
     *   <li>체험 → 유료 전환 시 기준일을 결제일 기준으로 다시 잡는다.</li>
     *   <li>누적 회차({@code billingCycleCount})와 마지막 결제 시각({@code lastBilledAt})을 기록한다.</li>
     * </ul>
     */
    public void recordBillingSuccess(Instant paidAt) {
        Objects.requireNonNull(paidAt, "paidAt");
        if (!status.isBillable()) {
            throw new InvalidStateTransitionException("갱신할 수 없는 상태입니다: " + status);
        }
        this.lastBilledAt = paidAt;
        if (cancelAtPeriodEnd) {
            expire();
            return;
        }
        boolean convertingFromTrial = status == SubscriptionStatus.TRIAL;
        if (status != SubscriptionStatus.ACTIVE) {
            transitionTo(SubscriptionStatus.ACTIVE);
        }
        if (convertingFromTrial) {
            this.anchorDay = effectiveBillingCycle().anchorDayFor(paidAt);
        }
        this.retryCount = 0;
        this.currentPeriodStart = paidAt.isAfter(currentPeriodEnd) ? paidAt : currentPeriodEnd;
        this.currentPeriodEnd = BillingCalendar.nextCycleEnd(this.currentPeriodStart, effectiveBillingCycle(), anchorDay);
        this.nextBillingAt = this.currentPeriodEnd;
        this.billingCycleCount++;
    }

    /** 주어진 기준 시각부터 새 주기를 연다(일시정지 해제, 수동 재개 등). */
    public void scheduleNextBilling(Instant base) {
        Objects.requireNonNull(base, "base");
        this.currentPeriodStart = base;
        this.currentPeriodEnd = BillingCalendar.nextChargeDate(base, effectiveBillingCycle(), anchorDay);
        this.nextBillingAt = this.currentPeriodEnd;
    }

    /** 기간 종료 시 해지를 예약한다. 이용 권한은 기간이 끝날 때까지 유지된다. */
    public void cancelAtPeriodEnd(Instant requestedAt) {
        if (!status.grantsAccess()) {
            throw new InvalidStateTransitionException("해지 예약할 수 없는 상태입니다: " + status);
        }
        this.cancelAtPeriodEnd = true;
        this.canceledAt = requestedAt;
    }

    /** 즉시 해지. 남은 기간은 환불 정책에 따라 서비스 계층에서 처리한다. */
    public void cancelNow(Instant requestedAt) {
        if (status == SubscriptionStatus.CANCELED) {
            return;
        }
        transitionTo(SubscriptionStatus.CANCELED);
        this.cancelAtPeriodEnd = false;
        this.canceledAt = requestedAt;
    }

    /**
     * 해지(사유 포함). 즉시/기간 종료 해지를 한 곳에서 처리한다.
     *
     * @param immediate true 면 지금 해지, false 면 기간 종료 시 해지
     */
    public void cancel(Instant requestedAt, String reason, boolean immediate) {
        this.cancelReason = reason;
        if (immediate) {
            cancelNow(requestedAt);
        } else {
            cancelAtPeriodEnd(requestedAt);
        }
    }

    /**
     * 해지된 구독을 관리자가 복구한다.
     *
     * <p>복구는 지금부터 새 이용 기간을 연다(끊긴 기간을 소급하지 않는다). 만료(EXPIRED)는 복구할 수 없다.
     */
    public void restore(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status != SubscriptionStatus.CANCELED) {
            throw new InvalidStateTransitionException("해지된 구독만 복구할 수 있습니다: " + status);
        }
        transitionTo(SubscriptionStatus.ACTIVE);
        this.cancelAtPeriodEnd = false;
        this.canceledAt = null;
        this.cancelReason = null;
        this.retryCount = 0;
        scheduleNextBilling(now);
    }

    /** 일시정지. 청구를 멈추고 다음 청구일을 비운다. */
    public void pause() {
        transitionTo(SubscriptionStatus.PAUSED);
        this.nextBillingAt = null;
    }

    /** 일시정지 해제. 지금부터 새 주기를 센다. */
    public void resume(Instant now) {
        transitionTo(SubscriptionStatus.ACTIVE);
        this.retryCount = 0;
        scheduleNextBilling(now);
    }

    /** 만료 처리. 이미 만료면 아무 일도 하지 않는다(배치 재실행 안전). */
    public void expire() {
        if (status == SubscriptionStatus.EXPIRED) {
            return;
        }
        transitionTo(SubscriptionStatus.EXPIRED);
    }

    /**
     * 해지 예약된 구독의 기간이 끝났으면 해지를 확정한다.
     *
     * @return 이번 호출로 해지가 확정되었으면 true
     */
    public boolean applyPendingCancellation(Instant now) {
        if (!cancelAtPeriodEnd || now.isBefore(currentPeriodEnd)) {
            return false;
        }
        if (status == SubscriptionStatus.CANCELED || status == SubscriptionStatus.EXPIRED) {
            return false;
        }
        transitionTo(SubscriptionStatus.CANCELED);
        this.cancelAtPeriodEnd = false;
        return true;
    }

    // ------------------------------------------------------------------
    // 변경 (해지/재생성 없이 같은 구독 안에서)
    // ------------------------------------------------------------------

    /**
     * 주기를 바꾸고 기준일을 다시 계산한다.
     *
     * <p>주의: 월 단위 → 주 단위로 바뀌면 기준일은 null 이 되고, 주 단위 → 월 단위로 바뀌면
     * 적용 시점의 일(day)이 새 기준일이 된다. 현재 회차({@code billingCycleCount})는 유지된다.
     */
    public void changeCycle(BillingCycle newCycle, EffectiveMode mode, Instant now) {
        Objects.requireNonNull(newCycle, "newCycle");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(now, "now");
        if (status.isTerminal()) {
            throw new InvalidStateTransitionException("만료된 구독의 주기는 바꿀 수 없습니다: " + status);
        }
        if (newCycle == effectiveBillingCycle()) {
            throw new IllegalArgumentException("이미 같은 주기입니다: " + newCycle);
        }
        this.billingCycle = newCycle;
        this.anchorDay = newCycle.anchorDayFor(now);
        if (mode.isImmediate()) {
            this.currentPeriodStart = now;
            this.nextBillingAt = BillingCalendar.nextChargeDate(now, newCycle, anchorDay);
        } else {
            Instant base = now.isAfter(currentPeriodEnd) ? now : currentPeriodEnd;
            this.nextBillingAt = BillingCalendar.nextCycleEnd(base, newCycle, anchorDay);
        }
    }

    /**
     * 플랜을 바꾼다. 이용 기간과 누적 회차는 유지하고, 주기가 달라질 때만 청구일을 다시 계산한다.
     *
     * @throws IllegalArgumentException 단종된 플랜으로 바꾸려 할 때
     */
    public void changePlan(Plan newPlan, EffectiveMode mode, Instant now) {
        Objects.requireNonNull(newPlan, "newPlan");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(now, "now");
        if (!newPlan.isActive()) {
            throw new IllegalArgumentException("단종된 플랜으로는 변경할 수 없습니다: " + newPlan.getCode());
        }
        if (status.isTerminal()) {
            throw new InvalidStateTransitionException("만료된 구독의 플랜은 바꿀 수 없습니다: " + status);
        }
        BillingCycle beforeCycle = effectiveBillingCycle();
        this.plan = newPlan;
        BillingCycle afterCycle = effectiveBillingCycle();
        if (afterCycle != beforeCycle) {
            this.anchorDay = afterCycle.anchorDayFor(now);
            this.nextBillingAt = mode.isImmediate()
                    ? BillingCalendar.nextChargeDate(now, afterCycle, anchorDay)
                    : BillingCalendar.nextCycleEnd(now.isAfter(currentPeriodEnd) ? now : currentPeriodEnd,
                            afterCycle, anchorDay);
        }
    }

    /** 수량을 바꾼다. 청구 금액은 다음 결제부터 새 수량으로 계산된다. */
    public void changeQuantity(int quantity) {
        if (status.isTerminal()) {
            throw new InvalidStateTransitionException("만료된 구독의 수량은 바꿀 수 없습니다: " + status);
        }
        this.quantity = validateQuantity(quantity);
    }

    /** 결제 기준일을 바꾼다(월 단위 주기만). 다음 청구일은 새 기준일로 다시 계산된다. */
    public void changeAnchorDay(int anchorDay, Instant now) {
        Objects.requireNonNull(now, "now");
        if (!effectiveBillingCycle().isMonthBased()) {
            throw new IllegalArgumentException("주 단위 구독은 결제일을 바꿀 수 없습니다: " + effectiveBillingCycle());
        }
        if (status.isTerminal()) {
            throw new InvalidStateTransitionException("만료된 구독의 결제일은 바꿀 수 없습니다: " + status);
        }
        this.anchorDay = BillingCalendar.requireValidAnchorDay(anchorDay);
        this.nextBillingAt = BillingCalendar.nextChargeDate(now, effectiveBillingCycle(), this.anchorDay);
    }

    // ------------------------------------------------------------------
    // 조회용 도메인 판단
    // ------------------------------------------------------------------

    /** 지금 청구해야 하는 구독인지. 해지 예약된 구독은 청구하지 않는다. */
    public boolean isDue(Instant now) {
        if (nextBillingAt == null || !status.isBillable() || cancelAtPeriodEnd) {
            return false;
        }
        return !nextBillingAt.isAfter(now);
    }

    /**
     * 중복 결제 가드에 걸리는지.
     *
     * @param now    현재 시각
     * @param window 마지막 결제 후 다시 청구하지 않을 최소 간격
     */
    public boolean isWithinDuplicateGuard(Instant now, java.time.Duration window) {
        if (lastBilledAt == null) {
            return false;
        }
        if (!now.isAfter(lastBilledAt)) {
            return true;
        }
        return java.time.Duration.between(lastBilledAt, now).compareTo(window) < 0;
    }

    /** 마지막 결제가 같은 UTC 날짜인지(배치/당겨받기 중복 청구 1차 가드). */
    public boolean isBilledOnSameDay(Instant now) {
        if (lastBilledAt == null) {
            return false;
        }
        return lastBilledAt.atZone(java.time.ZoneOffset.UTC).toLocalDate()
                .equals(now.atZone(java.time.ZoneOffset.UTC).toLocalDate());
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public boolean isTrial() {
        return status == SubscriptionStatus.TRIAL;
    }

    /** 플랜·주기·수량을 바꿀 수 있는 상태인지(이용 중이거나 체험 중). */
    public boolean isChangeable() {
        return status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.TRIAL;
    }

    /** Dunning 재시도 대상이 될 수 있는 상태인지. 해지(CANCELED)·만료는 제외한다. */
    public boolean isDunningEligible() {
        return status == SubscriptionStatus.PAST_DUE;
    }

    public void changeBillingKey(BillingKey newBillingKey) {
        Objects.requireNonNull(newBillingKey, "newBillingKey");
        newBillingKey.ensureUsable();
        this.billingKey = newBillingKey;
    }

    /** 실제 청구에 쓰는 주기. override 가 없으면 플랜 주기를 따른다. */
    public BillingCycle effectiveBillingCycle() {
        return billingCycle != null ? billingCycle : plan.getBillingCycle();
    }

    /** 현재 회차(정상 결제 기준). 다음 결제는 {@code billingCycleCount + 1} 회차다. */
    public int nextBillingCycleNumber() {
        return billingCycleCount + 1;
    }

    /** 이번에 청구할 금액(플랜 단가 × 수량). */
    public BigDecimal nextBillingAmount() {
        return plan.amountFor(quantity);
    }

    /** 현재 플랜 기준 통화. */
    public String currency() {
        return plan.getCurrency();
    }

    private static int validateQuantity(int quantity) {
        if (quantity < 1 || quantity > 9999) {
            throw new IllegalArgumentException("수량은 1 이상 9999 이하여야 합니다: " + quantity);
        }
        return quantity;
    }
}
