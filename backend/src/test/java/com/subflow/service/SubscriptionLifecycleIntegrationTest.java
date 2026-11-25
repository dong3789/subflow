package com.subflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subflow.common.error.BusinessException;
import com.subflow.domain.audit.ActorType;
import com.subflow.domain.audit.AuditLog;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.plan.Plan;
import com.subflow.domain.subscription.ChangeType;
import com.subflow.domain.subscription.EffectiveMode;
import com.subflow.domain.subscription.Subscription;
import com.subflow.domain.subscription.SubscriptionChangeHistory;
import com.subflow.domain.subscription.SubscriptionStatus;
import com.subflow.outbox.OutboxEventTypes;
import com.subflow.support.IntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 구독 생애주기 통합 테스트 — 주기/플랜/수량/결제일 변경, 해지, 복구.
 *
 * <p>핵심 검증: 변경이 <b>해지/재생성 없이</b> 같은 구독에서 이뤄지고(회차 유지),
 * 기준일이 명시적으로 다시 계산되며, 모든 변경이 이력과 감사 로그로 남는지.
 */
class SubscriptionLifecycleIntegrationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("4주 → 월 주기 변경 시 기준일 재계산·미리보기·이력·감사 로그가 남는다")
    void cycleChangeRecalculatesAnchor() {
        Subscription subscription = createActiveSubscription("3900", BillingCycle.FOUR_WEEKLY);
        Instant changedAt = Instant.parse("2026-12-02T00:00:00Z");

        CycleChangePreview preview = lifecycleService.previewCycleChange(
                subscription.getId(), BillingCycle.MONTHLY, EffectiveMode.NEXT_CYCLE, changedAt);
        assertThat(preview.currentAnchorDay()).isNull();
        assertThat(preview.newAnchorDay()).isEqualTo(2);
        assertThat(preview.previewNextBillingAt()).isEqualTo(Instant.parse("2027-01-02T00:00:00Z"));

        lifecycleService.changeCycle(subscription.getId(), BillingCycle.MONTHLY, EffectiveMode.NEXT_CYCLE,
                "고객 요청: 매월 결제", ActorType.USER, "user-1", changedAt);

        Subscription reloaded = reload(subscription.getId());
        assertThat(reloaded.effectiveBillingCycle()).isEqualTo(BillingCycle.MONTHLY);
        assertThat(reloaded.getAnchorDay()).isEqualTo(2);
        assertThat(reloaded.getNextBillingAt()).isEqualTo(Instant.parse("2027-01-02T00:00:00Z"));
        assertThat(reloaded.getBillingCycleCount()).isZero();

        List<SubscriptionChangeHistory> histories = changeHistoryRepository
                .findBySubscriptionIdOrderByCreatedAtDescIdDesc(subscription.getId());
        assertThat(histories).hasSize(1);
        SubscriptionChangeHistory history = histories.get(0);
        assertThat(history.getChangeType()).isEqualTo(ChangeType.CYCLE_CHANGE);
        assertThat(history.getEffectiveMode()).isEqualTo(EffectiveMode.NEXT_CYCLE);
        assertThat(history.getBeforeState()).contains("FOUR_WEEKLY").contains("\"anchorDay\":null");
        assertThat(history.getAfterState()).contains("MONTHLY").contains("\"anchorDay\":2");
        assertThat(history.getReason()).isEqualTo("고객 요청: 매월 결제");
        assertThat(history.getActorType()).isEqualTo(ActorType.USER);

        List<AuditLog> audits = auditLogRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                "SUBSCRIPTION", subscription.getPublicId());
        assertThat(audits).extracting(AuditLog::getAction).contains("SUBSCRIPTION_CYCLE_CHANGED");
    }

    @Test
    @DisplayName("월 → 주 주기 변경은 기준일을 없앤다")
    void monthlyToWeeklyClearsAnchor() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);

        lifecycleService.changeCycle(subscription.getId(), BillingCycle.WEEKLY, EffectiveMode.NEXT_CYCLE,
                "주간 배송으로 전환", ActorType.USER, "user-1", T0.plusSeconds(86400));

        Subscription reloaded = reload(subscription.getId());
        assertThat(reloaded.getAnchorDay()).isNull();
        assertThat(reloaded.getNextBillingAt())
                .as("기간 종료(12/25) + 7일")
                .isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("플랜을 바꿔도 같은 구독의 누적 회차가 유지되고 이력이 남는다")
    void planChangeKeepsCycleCount() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        billingService.charge(subscription.getId(), BillingReason.SCHEDULED, DUE);
        assertThat(reload(subscription.getId()).getBillingCycleCount()).isEqualTo(1);

        Plan premium = createPlan("PREMIUM", "29900", BillingCycle.MONTHLY);
        lifecycleService.changePlan(subscription.getId(), premium.getId(), EffectiveMode.NEXT_CYCLE,
                "상위 플랜으로 변경", ActorType.USER, "user-1", DUE.plusSeconds(86400));

        Subscription reloaded = reload(subscription.getId());
        assertThat(reloaded.getId()).as("구독은 그대로, 해지/재생성 없음").isEqualTo(subscription.getId());
        assertThat(reloaded.getPlan().getCode()).isEqualTo("PREMIUM");
        assertThat(reloaded.getBillingCycleCount()).isEqualTo(1);
        assertThat(reloaded.nextBillingAmount()).isEqualByComparingTo("29900");
        assertThat(reloaded.getNextBillingAt()).as("같은 주기라 청구일 유지")
                .isEqualTo(Instant.parse("2027-01-25T00:00:00Z"));

        List<SubscriptionChangeHistory> histories = changeHistoryRepository
                .findBySubscriptionIdOrderByCreatedAtDescIdDesc(subscription.getId());
        assertThat(histories).extracting(SubscriptionChangeHistory::getChangeType)
                .containsExactly(ChangeType.PLAN_CHANGE);
        assertThat(histories.get(0).getBeforeState()).contains("PLAN_").contains("\"billingCycleCount\":1");
        assertThat(histories.get(0).getAfterState()).contains("PREMIUM");
    }

    @Test
    @DisplayName("단종 플랜은 신규 구독도 플랜 변경도 막는다")
    void inactivePlanBlocksCreationAndChange() {
        Plan retired = createPlan("RETIRED", "9900", BillingCycle.MONTHLY);
        retired.deactivate();
        planRepository.save(retired);

        var customer = createCustomer();
        var billingKey = createBillingKey(customer);
        assertThatThrownBy(() -> lifecycleService.create(
                new CreateSubscriptionCommand(customer.getId(), retired.getId(), billingKey.getId(), 1),
                ActorType.USER, "user-1", T0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("단종");

        Subscription active = createActiveSubscription("4900", BillingCycle.MONTHLY);
        assertThatThrownBy(() -> lifecycleService.changePlan(active.getId(), retired.getId(),
                EffectiveMode.NEXT_CYCLE, "단종 플랜으로", ActorType.USER, "user-1", T0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("단종");
    }

    @Test
    @DisplayName("수량 변경은 다음 청구 금액을 바꾸고 이력을 남긴다")
    void quantityChangeUpdatesAmount() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);

        lifecycleService.changeQuantity(subscription.getId(), 3, EffectiveMode.NEXT_CYCLE,
                "좌석 3개", ActorType.USER, "user-1");

        Subscription reloaded = reload(subscription.getId());
        assertThat(reloaded.getQuantity()).isEqualTo(3);
        assertThat(reloaded.nextBillingAmount()).isEqualByComparingTo("29700");
        assertThat(changeHistoryRepository.findBySubscriptionIdOrderByCreatedAtDescIdDesc(subscription.getId()))
                .extracting(SubscriptionChangeHistory::getChangeType)
                .containsExactly(ChangeType.QUANTITY_CHANGE);
    }

    @Test
    @DisplayName("결제일 변경은 다음 청구일을 새 기준일로 옮긴다")
    void billingDateChangeMovesNextBilling() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);

        lifecycleService.changeBillingDate(subscription.getId(), 1, EffectiveMode.IMMEDIATE,
                "월초 결제로 변경", ActorType.USER, "user-1", T0);

        Subscription reloaded = reload(subscription.getId());
        assertThat(reloaded.getAnchorDay()).isEqualTo(1);
        assertThat(reloaded.getNextBillingAt()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(changeHistoryRepository.findBySubscriptionIdOrderByCreatedAtDescIdDesc(subscription.getId()))
                .extracting(SubscriptionChangeHistory::getChangeType)
                .containsExactly(ChangeType.BILLING_DATE_CHANGE);
    }

    @Test
    @DisplayName("사유 있는 즉시 해지는 상태·이력·감사 로그·Outbox 를 모두 남긴다")
    void immediateCancelRecordsEverything() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);

        lifecycleService.cancel(subscription.getId(), "배송이 너무 늦어요", true,
                ActorType.USER, "user-1", T0.plusSeconds(3600));

        Subscription reloaded = reload(subscription.getId());
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(reloaded.getCancelReason()).isEqualTo("배송이 너무 늦어요");
        assertThat(reloaded.getNextBillingAt()).isNull();

        assertThat(changeHistoryRepository.findBySubscriptionIdOrderByCreatedAtDescIdDesc(subscription.getId()))
                .extracting(SubscriptionChangeHistory::getChangeType).containsExactly(ChangeType.CANCEL);
        assertThat(auditLogRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                "SUBSCRIPTION", subscription.getPublicId()))
                .extracting(AuditLog::getAction).contains("SUBSCRIPTION_CANCELED");
        assertThat(outboxEventRepository
                .findByAggregateTypeAndAggregateIdOrderByIdAsc("Subscription", String.valueOf(subscription.getId())))
                .extracting(event -> event.getEventType())
                .contains(OutboxEventTypes.SUBSCRIPTION_CANCELED);
    }

    @Test
    @DisplayName("기간 종료 해지는 기간이 끝날 때 배치가 확정한다")
    void periodEndCancellationAppliedByBatch() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);

        lifecycleService.cancel(subscription.getId(), "이번 달까지만", false,
                ActorType.USER, "user-1", T0.plusSeconds(3600));
        assertThat(reload(subscription.getId()).getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(reload(subscription.getId()).isCancelAtPeriodEnd()).isTrue();

        List<Subscription> applied = lifecycleService.applyPendingCancellations(DUE);
        assertThat(applied).hasSize(1);
        assertThat(reload(subscription.getId()).getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(auditLogRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                "SUBSCRIPTION", subscription.getPublicId()))
                .extracting(AuditLog::getAction).contains("SUBSCRIPTION_CANCELED");
    }

    @Test
    @DisplayName("관리자 복구는 해지 구독을 새 기간으로 되살리고 이력을 남긴다")
    void adminRestoreReopensSubscription() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        lifecycleService.cancel(subscription.getId(), "실수로 해지", true, ActorType.USER, "user-1",
                T0.plusSeconds(3600));

        Instant restoredAt = Instant.parse("2026-12-10T00:00:00Z");
        lifecycleService.restore(subscription.getId(), ActorType.ADMIN, "admin-1", restoredAt);

        Subscription reloaded = reload(subscription.getId());
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(reloaded.getCancelReason()).isNull();
        assertThat(reloaded.getNextBillingAt()).as("다음 결제일은 기준일(25일)로 맞춰진다")
                .isEqualTo(Instant.parse("2026-12-25T00:00:00Z"));
        assertThat(changeHistoryRepository.findBySubscriptionIdOrderByCreatedAtDescIdDesc(subscription.getId()))
                .extracting(SubscriptionChangeHistory::getChangeType)
                .containsExactly(ChangeType.RESTORE, ChangeType.CANCEL);
        assertThat(auditLogRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                "SUBSCRIPTION", subscription.getPublicId()))
                .extracting(AuditLog::getAction).contains("SUBSCRIPTION_RESTORED");
    }

    @Test
    @DisplayName("해지된 구독은 플랜·주기 변경을 할 수 없다")
    void canceledSubscriptionCannotChange() {
        Subscription subscription = createActiveSubscription("9900", BillingCycle.MONTHLY);
        lifecycleService.cancel(subscription.getId(), "해지", true, ActorType.USER, "user-1", T0);

        assertThatThrownBy(() -> lifecycleService.changeQuantity(subscription.getId(), 2,
                EffectiveMode.NEXT_CYCLE, null, ActorType.USER, "user-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이용 중");
    }
}
