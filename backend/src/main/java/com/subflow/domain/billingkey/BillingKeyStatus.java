package com.subflow.domain.billingkey;

/** 빌링키 상태. */
public enum BillingKeyStatus {

    /** 사용 가능. 정기결제에 쓸 수 있다. */
    ACTIVE,

    /** 카드 변경 등으로 비활성화. 이력 보존용. */
    INACTIVE,

    /** PG 또는 사용자가 폐기. 재사용 불가. */
    REVOKED;

    public boolean isUsable() {
        return this == ACTIVE;
    }
}
