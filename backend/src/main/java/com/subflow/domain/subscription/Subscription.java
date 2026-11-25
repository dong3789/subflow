package com.subflow.domain.subscription;

import com.subflow.common.error.InvalidStateTransitionException;
import com.subflow.domain.billingkey.BillingKey;
import com.subflow.domain.customer.Customer;
import com.subflow.domain.plan.Plan;
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
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구독 애그리거트.
 *
 * <p>상태 전이는 {@link SubscriptionStatus}의 규칙을 따르며, 외부에서 상태를 직접 대입할 수 없다.
 * 결제 실행 서비스(2단계)는 이 엔티티의 도메인 메서드만 호출하고, 그 결과를 결제 원장({@code Payment})에 남긴다.
 *
 * <p>동시성: 낙관적 락({@link Version})을 기본으로 하고, 정기결제 배치처럼 경합이 예상되는 경로는
 * Redisson 분산 락(2단계)으로 구독 단위 직렬화를 건다.
 */
@Entity
@Table(name = "subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    private Subscription(Customer customer, Plan plan, BillingKey billingKey, Instant now) {
        this.customer = Objects.requireNonNull(customer, "customer");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.billingKey = billingKey;
        this.status = plan.hasTrial() ? SubscriptionStatus.TRIAL : SubscriptionStatus.ACTIVE;
        this.currentPeriodStart = now;
        this.currentPeriodEnd = plan.initialPeriodEnd(now);
        this.nextBillingAt = this.currentPeriodEnd;
        this.retryCount = 0;
        this.cancelAtPeriodEnd = false;
    }

    /**
     * 구독을 시작한다. 체험 플랜이면 TRIAL, 아니면 ACTIVE로 시작하며 첫 청구일은 주기 종료 시점이다.
     *
     * @throws IllegalArgumentException 체험 없는 플랜인데 빌링키가 없을 때
     */
    public static Subscription start(Customer customer, Plan plan, BillingKey billingKey, Instant now) {
        Objects.requireNonNull(now, "now");
        if (!plan.hasTrial() && billingKey == null) {
            throw new IllegalArgumentException("체험 없는 플랜은 빌링키가 필요합니다: plan=" + plan.getCode());
        }
        if (billingKey != null) {
            billingKey.ensureUsable();
        }
        return new Subscription(customer, plan, billingKey, now);
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
     * 다음 재시도 시각을 잡는다.
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
        this.currentPeriodEnd = plan.getBillingCycle().advance(this.currentPeriodStart);
        this.nextBillingAt = this.currentPeriodEnd;
    }

    /** 주어진 기준 시각부터 새 주기를 연다(일시정지 해제, 수동 재개 등). */
    public void scheduleNextBilling(Instant base) {
        Objects.requireNonNull(base, "base");
        this.currentPeriodStart = base;
        this.currentPeriodEnd = plan.getBillingCycle().advance(base);
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
    // 조회용 도메인 판단
    // ------------------------------------------------------------------

    /** 지금 청구해야 하는 구독인지. 해지 예약된 구독은 청구하지 않는다. */
    public boolean isDue(Instant now) {
        if (nextBillingAt == null || !status.isBillable() || cancelAtPeriodEnd) {
            return false;
        }
        return !nextBillingAt.isAfter(now);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public boolean isTrial() {
        return status == SubscriptionStatus.TRIAL;
    }

    public void changeBillingKey(BillingKey newBillingKey) {
        Objects.requireNonNull(newBillingKey, "newBillingKey");
        newBillingKey.ensureUsable();
        this.billingKey = newBillingKey;
    }

    /** 현재 플랜 기준 다음 청구 금액. */
    public java.math.BigDecimal nextBillingAmount() {
        return plan.getAmount();
    }

    /** 현재 플랜 기준 통화. */
    public String currency() {
        return plan.getCurrency();
    }
}
