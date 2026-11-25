package com.subflow.outbox;

/** Outbox 이벤트 타입(외부로 나가는 웹훅 이벤트 이름). */
public final class OutboxEventTypes {

    private OutboxEventTypes() {
    }

    public static final String SUBSCRIPTION_CREATED = "subscription.created";
    public static final String SUBSCRIPTION_RENEWED = "subscription.renewed";
    public static final String SUBSCRIPTION_PAST_DUE = "subscription.past_due";
    public static final String SUBSCRIPTION_EXPIRED = "subscription.expired";
    public static final String SUBSCRIPTION_CANCELED = "subscription.canceled";
    public static final String SUBSCRIPTION_RESTORED = "subscription.restored";
    public static final String SUBSCRIPTION_PLAN_CHANGED = "subscription.plan_changed";
    public static final String SUBSCRIPTION_CYCLE_CHANGED = "subscription.cycle_changed";
    public static final String SUBSCRIPTION_QUANTITY_CHANGED = "subscription.quantity_changed";
    public static final String SUBSCRIPTION_BILLING_DATE_CHANGED = "subscription.billing_date_changed";

    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String PAYMENT_PENDING_CONFIRMATION = "payment.pending_confirmation";
    public static final String PAYMENT_CANCELED = "payment.canceled";
}
