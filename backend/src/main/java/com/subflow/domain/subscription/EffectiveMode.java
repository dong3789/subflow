package com.subflow.domain.subscription;

/** 변경 적용 시점. */
public enum EffectiveMode {

    /** 즉시 적용(다음 결제일을 지금 기준으로 다시 계산). */
    IMMEDIATE,

    /** 다음 주기부터 적용(현재 이용 기간은 그대로). */
    NEXT_CYCLE;

    public boolean isImmediate() {
        return this == IMMEDIATE;
    }
}
