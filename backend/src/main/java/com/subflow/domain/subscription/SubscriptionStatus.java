package com.subflow.domain.subscription;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 구독 상태와 상태 전이 규칙.
 *
 * <pre>
 * TRIAL ──▶ ACTIVE ──▶ PAST_DUE ──▶ ACTIVE (Dunning 성공)
 *   │         │            │
 *   │         │            └──▶ EXPIRED (재시도 한도 초과)
 *   │         ├──▶ PAUSED ──▶ ACTIVE
 *   │         └──▶ CANCELED ──▶ EXPIRED (기간 종료)
 *   └──▶ CANCELED / EXPIRED
 * </pre>
 *
 * <p>전이 규칙은 이 enum이 단일 출처(single source of truth)다. 서비스 계층은 상태를 직접 대입하지 않고
 * {@code Subscription}의 도메인 메서드를 통해서만 바꾼다.
 */
public enum SubscriptionStatus {

    /** 체험 기간. 결제 없이 이용 중이며, 체험 종료 시 첫 결제가 발생한다. */
    TRIAL("체험중"),

    /** 정상 구독. 다음 청구일이 설정되어 있다. */
    ACTIVE("이용중"),

    /** 연체. 결제 실패 후 Dunning 재시도 대기 상태. */
    PAST_DUE("연체"),

    /** 일시정지. 청구를 멈추고 기간을 소비하지 않는다. */
    PAUSED("일시정지"),

    /** 해지됨. 더 이상 청구하지 않는다. */
    CANCELED("해지"),

    /** 만료. 재시도 한도 초과 또는 기간 종료로 구독이 끝났다. */
    EXPIRED("만료");

    private final String label;

    SubscriptionStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 더 이상 전이하지 않는 최종 상태인지. */
    public boolean isTerminal() {
        return this == EXPIRED;
    }

    /** 청구 대상(다음 결제를 시도해야 하는) 상태인지. */
    public boolean isBillable() {
        return this == TRIAL || this == ACTIVE || this == PAST_DUE;
    }

    /** 이용 가능한(서비스 접근을 허용하는) 상태인지. */
    public boolean grantsAccess() {
        return this == TRIAL || this == ACTIVE || this == PAST_DUE;
    }

    public Set<SubscriptionStatus> allowedTransitions() {
        return switch (this) {
            case TRIAL -> EnumSet.of(ACTIVE, PAST_DUE, CANCELED, EXPIRED);
            case ACTIVE -> EnumSet.of(PAST_DUE, PAUSED, CANCELED, EXPIRED);
            case PAST_DUE -> EnumSet.of(ACTIVE, CANCELED, EXPIRED);
            case PAUSED -> EnumSet.of(ACTIVE, CANCELED, EXPIRED);
            case CANCELED -> EnumSet.of(EXPIRED);
            case EXPIRED -> Collections.unmodifiableSet(EnumSet.noneOf(SubscriptionStatus.class));
        };
    }

    /**
     * 현재 상태에서 목표 상태로 전이할 수 있는지 검증한다.
     *
     * @param target 목표 상태 (null이면 false)
     */
    public boolean canTransitionTo(SubscriptionStatus target) {
        return target != null && allowedTransitions().contains(target);
    }
}
