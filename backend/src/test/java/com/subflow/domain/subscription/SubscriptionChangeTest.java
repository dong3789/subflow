package com.subflow.domain.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subflow.common.error.InvalidStateTransitionException;
import com.subflow.domain.billingkey.BillingKey;
import com.subflow.domain.customer.Customer;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.plan.Plan;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 구독 변경 도메인 규칙 — 주기/플랜/수량/결제일/해지/복구.
 *
 * <p>DB 없이 엔티티만으로 검증한다. 서비스가 아니라 도메인이 지켜야 하는 규칙
 * (회차 유지, 기준일 재계산, 단종 플랜 차단, 중복 결제 가드)을 여기서 못 박는다.
 */
class SubscriptionChangeTest {

    private static final Instant T0 = Instant.parse("2026-11-25T00:00:00Z");

    private Customer customer;
    private Plan monthly;
    private Plan fourWeekly;
    private Plan yearly;
    private BillingKey billingKey;

    @BeforeEach
    void setUp() {
        customer = Customer.register("change@example.com", "변경", "ext-change");
        monthly = Plan.create("CHANGE_MONTHLY", "월간", new BigDecimal("9900"), "KRW",
                BillingCycle.MONTHLY, 0, null);
        fourWeekly = Plan.create("CHANGE_4W", "4주", new BigDecimal("3900"), "KRW",
                BillingCycle.FOUR_WEEKLY, 0, null);
        yearly = Plan.create("CHANGE_YEARLY", "연간", new BigDecimal("99000"), "KRW",
                BillingCycle.YEARLY, 0, null);
        billingKey = BillingKey.issue(customer, "MOCK", "token", "1234");
    }

    private Subscription subscriptionOf(Plan plan) {
        return Subscription.start(customer, plan, billingKey, T0);
    }

    @Test
    @DisplayName("4주 주기 구독은 기준일이 없고, 월 주기로 바꾸면 기준일이 생긴다")
    void changingFromWeeklyToMonthlyCreatesAnchor() {
        Subscription subscription = subscriptionOf(fourWeekly);
        assertThat(subscription.getAnchorDay()).isNull();
        assertThat(subscription.effectiveBillingCycle()).isEqualTo(BillingCycle.FOUR_WEEKLY);

        Instant changedAt = Instant.parse("2026-12-02T00:00:00Z");
        subscription.changeCycle(BillingCycle.MONTHLY, EffectiveMode.NEXT_CYCLE, changedAt);

        assertThat(subscription.getAnchorDay()).isEqualTo(2);
        assertThat(subscription.effectiveBillingCycle()).isEqualTo(BillingCycle.MONTHLY);
        assertThat(subscription.getNextBillingAt())
                .as("기간 종료(12/23) 뒤 기준일 2일 → 2027-01-02")
                .isEqualTo(Instant.parse("2027-01-02T00:00:00Z"));
        assertThat(subscription.getBillingCycleCount()).as("변경해도 회차는 유지").isZero();
    }

    @Test
    @DisplayName("월 주기에서 주 주기로 바꾸면 기준일이 사라진다")
    void changingFromMonthlyToWeeklyClearsAnchor() {
        Subscription subscription = subscriptionOf(monthly);
        assertThat(subscription.getAnchorDay()).isEqualTo(25);

        subscription.changeCycle(BillingCycle.WEEKLY, EffectiveMode.NEXT_CYCLE, T0);

        assertThat(subscription.getAnchorDay()).isNull();
        assertThat(subscription.getNextBillingAt())
                .as("기간 종료(12/25) + 7일")
                .isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("즉시 적용 주기 변경은 지금부터 새 주기를 센다")
    void immediateCycleChangeStartsFromNow() {
        Subscription subscription = subscriptionOf(monthly);
        Instant changedAt = Instant.parse("2026-12-02T00:00:00Z");

        subscription.changeCycle(BillingCycle.FOUR_WEEKLY, EffectiveMode.IMMEDIATE, changedAt);

        assertThat(subscription.getCurrentPeriodStart()).isEqualTo(changedAt);
        assertThat(subscription.getNextBillingAt()).isEqualTo(Instant.parse("2026-12-30T00:00:00Z"));
        assertThat(subscription.getAnchorDay()).isNull();
    }

    @Test
    @DisplayName("같은 주기로 바꾸는 것은 의미 없는 요청이라 거부한다")
    void rejectsSameCycle() {
        Subscription subscription = subscriptionOf(monthly);

        assertThatThrownBy(() -> subscription.changeCycle(BillingCycle.MONTHLY, EffectiveMode.NEXT_CYCLE, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("같은 주기");
    }

    @Test
    @DisplayName("플랜을 바꿔도 누적 결제 회차는 유지된다")
    void planChangeKeepsBillingCycleCount() {
        Subscription subscription = subscriptionOf(monthly);
        subscription.recordBillingSuccess(Instant.parse("2026-12-25T00:00:00Z"));
        assertThat(subscription.getBillingCycleCount()).isEqualTo(1);

        subscription.changePlan(yearly, EffectiveMode.NEXT_CYCLE, Instant.parse("2026-12-26T00:00:00Z"));

        assertThat(subscription.getPlan().getCode()).isEqualTo("CHANGE_YEARLY");
        assertThat(subscription.getBillingCycleCount()).as("해지/재생성 없이 회차 유지").isEqualTo(1);
        assertThat(subscription.getAnchorDay()).isEqualTo(26);
    }

    @Test
    @DisplayName("단종된 플랜으로는 변경할 수 없다")
    void rejectsInactivePlanChange() {
        Subscription subscription = subscriptionOf(monthly);
        yearly.deactivate();

        assertThatThrownBy(() -> subscription.changePlan(yearly, EffectiveMode.NEXT_CYCLE, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("단종");
    }

    @Test
    @DisplayName("수량을 바꾸면 청구 금액이 단가 × 수량으로 계산된다")
    void quantityChangesAmount() {
        Subscription subscription = subscriptionOf(monthly);
        assertThat(subscription.nextBillingAmount()).isEqualByComparingTo("9900");

        subscription.changeQuantity(3);

        assertThat(subscription.getQuantity()).isEqualTo(3);
        assertThat(subscription.nextBillingAmount()).isEqualByComparingTo("29700");
    }

    @Test
    @DisplayName("수량은 1 이상이어야 한다")
    void rejectsInvalidQuantity() {
        Subscription subscription = subscriptionOf(monthly);

        assertThatThrownBy(() -> subscription.changeQuantity(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결제일을 바꾸면 다음 청구일이 새 기준일로 다시 계산된다")
    void changingAnchorDayRecomputesNextBilling() {
        Subscription subscription = subscriptionOf(monthly);

        subscription.changeAnchorDay(1, T0);

        assertThat(subscription.getAnchorDay()).isEqualTo(1);
        assertThat(subscription.getNextBillingAt())
                .as("11/25 에서 다음 1일은 12/1")
                .isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
    }

    @Test
    @DisplayName("주 단위 구독은 결제일을 바꿀 수 없다")
    void weeklySubscriptionCannotChangeBillingDate() {
        Subscription subscription = subscriptionOf(fourWeekly);

        assertThatThrownBy(() -> subscription.changeAnchorDay(1, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("주 단위");
    }

    @Test
    @DisplayName("해지 사유가 기록되고 즉시 해지/기간 종료 해지가 구분된다")
    void cancelRecordsReason() {
        Subscription immediate = subscriptionOf(monthly);
        immediate.cancel(T0.plusSeconds(60), "고객 요청", true);

        assertThat(immediate.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(immediate.getCancelReason()).isEqualTo("고객 요청");
        assertThat(immediate.getNextBillingAt()).isNull();

        Subscription scheduled = subscriptionOf(monthly);
        scheduled.cancel(T0.plusSeconds(60), "다음 달까지 사용", false);

        assertThat(scheduled.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(scheduled.isCancelAtPeriodEnd()).isTrue();
        assertThat(scheduled.getCancelReason()).isEqualTo("다음 달까지 사용");
        assertThat(scheduled.getNextBillingAt()).as("해지 예약 구독은 청구하지 않는다").isEqualTo(scheduled.getCurrentPeriodEnd());
    }

    @Test
    @DisplayName("관리자 복구는 새 기간을 열고 해지 사유를 지운다")
    void restoreOpensNewPeriod() {
        Subscription subscription = subscriptionOf(monthly);
        subscription.cancel(T0.plusSeconds(60), "실수로 해지", true);

        Instant restoredAt = Instant.parse("2026-12-10T00:00:00Z");
        subscription.restore(restoredAt);

        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getCancelReason()).isNull();
        assertThat(subscription.getCanceledAt()).isNull();
        assertThat(subscription.getCurrentPeriodStart()).isEqualTo(restoredAt);
        assertThat(subscription.getNextBillingAt()).as("복구해도 결제일은 기준일(25일)로 돌아간다")
                .isEqualTo(Instant.parse("2026-12-25T00:00:00Z"));
    }

    @Test
    @DisplayName("정상 결제는 회차를 올리고 마지막 결제 시각을 남긴다")
    void billingSuccessCountsCycle() {
        Subscription subscription = subscriptionOf(monthly);

        subscription.recordBillingSuccess(Instant.parse("2026-12-25T00:00:00Z"));

        assertThat(subscription.getBillingCycleCount()).isEqualTo(1);
        assertThat(subscription.getLastBilledAt()).isEqualTo(Instant.parse("2026-12-25T00:00:00Z"));
        assertThat(subscription.getCurrentPeriodStart()).isEqualTo(Instant.parse("2026-12-25T00:00:00Z"));
        assertThat(subscription.getNextBillingAt()).isEqualTo(Instant.parse("2027-01-25T00:00:00Z"));
    }

    @Test
    @DisplayName("보호 윈도우 안에서는 중복 청구로 판단한다")
    void detectsDuplicateWithinWindow() {
        Subscription subscription = subscriptionOf(monthly);
        Instant billedAt = Instant.parse("2026-12-25T00:00:00Z");
        subscription.recordBillingSuccess(billedAt);

        assertThat(subscription.isBilledOnSameDay(billedAt.plusSeconds(3600))).isTrue();
        assertThat(subscription.isWithinDuplicateGuard(billedAt.plusSeconds(3600),
                java.time.Duration.ofHours(24))).isTrue();
        assertThat(subscription.isWithinDuplicateGuard(billedAt.plusSeconds(24 * 3600),
                java.time.Duration.ofHours(24))).isFalse();
    }

    @Test
    @DisplayName("만료된 구독은 변경할 수 없다")
    void expiredCannotChange() {
        Subscription subscription = subscriptionOf(monthly);
        subscription.expire();

        assertThatThrownBy(() -> subscription.changeQuantity(2))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> subscription.changeCycle(BillingCycle.WEEKLY, EffectiveMode.NEXT_CYCLE, T0))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
