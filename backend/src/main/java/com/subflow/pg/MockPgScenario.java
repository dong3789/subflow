package com.subflow.pg;

/**
 * Mock PG 시나리오. 테스트·데모에서 실무에서 만나는 실패 유형을 그대로 재현한다.
 */
public enum MockPgScenario {

    /** 정상 승인. */
    APPROVE("APPROVE", false),

    /** 잔액 부족 — 일시 실패(다음 재시도에서 성공할 수 있다). */
    INSUFFICIENT_FUNDS("INSUFFICIENT_FUNDS", true),

    /** 카드 한도 초과 — 일시 실패. */
    CARD_LIMIT_EXCEEDED("CARD_LIMIT_EXCEEDED", true),

    /** 카드 정지/분실 — 재시도로 해결되지 않는 실패(카드 교체 필요). */
    CARD_DECLINED("CARD_DECLINED", false),

    /** 빌링키 무효 — 재시도로 해결되지 않는 실패. */
    INVALID_BILLING_KEY("INVALID_BILLING_KEY", false),

    /** 네트워크 타임아웃 — 승인 여부 미확정(원장은 PENDING 으로 남는다). */
    NETWORK_TIMEOUT("NETWORK_TIMEOUT", true);

    private final String code;
    private final boolean retryable;

    MockPgScenario(String code, boolean retryable) {
        this.code = code;
        this.retryable = retryable;
    }

    public String getCode() {
        return code;
    }

    /** 재시도로 성공할 수 있는 실패인지. */
    public boolean isRetryable() {
        return retryable;
    }
}
