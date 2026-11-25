package com.subflow.domain.payment;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** 결제 원장 상태. */
public enum PaymentStatus {

    /** 승인 요청 전/요청 중. */
    PENDING,

    /** 승인 성공. 이 상태만 매출로 집계한다. */
    SUCCEEDED,

    /** 승인 실패(한도 초과, 카드 정지 등). 같은 멱등키로 재승인하지 않고 새 시도를 만든다. */
    FAILED,

    /** 승인 후 취소/환불. */
    CANCELED;

    public boolean isTerminal() {
        return this != PENDING;
    }

    public boolean isSuccessful() {
        return this == SUCCEEDED;
    }

    public Set<PaymentStatus> allowedTransitions() {
        return switch (this) {
            case PENDING -> EnumSet.of(SUCCEEDED, FAILED, CANCELED);
            case SUCCEEDED -> EnumSet.of(CANCELED);
            case FAILED, CANCELED -> Collections.unmodifiableSet(EnumSet.noneOf(PaymentStatus.class));
        };
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return target != null && allowedTransitions().contains(target);
    }
}
