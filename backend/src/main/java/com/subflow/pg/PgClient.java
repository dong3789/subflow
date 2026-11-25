package com.subflow.pg;

/**
 * PG 어댑터.
 *
 * <p>결제 코어는 이 인터페이스에만 의존한다. 실제 PG(토스페이먼츠/포트원)를 붙일 때는 구현체만 갈아 끼우면 되고,
 * 개발·테스트에서는 {@link MockPgClient} 로 시나리오(승인·한도 초과·타임아웃·멱등 재응답)를 재현한다.
 */
public interface PgClient {

    /**
     * 빌링키로 승인을 요청한다.
     *
     * @throws PgTimeoutException PG 응답을 받지 못했을 때(승인 여부 미확정)
     */
    ChargeResult chargeWithBillingKey(ChargeRequest request);

    /** 승인된 거래를 취소/환불한다. */
    CancelResult cancelPayment(String pgTxId, String reason);

    /** PG 쪽 거래 상태를 조회한다(정합성 점검). */
    PaymentStatusResult fetchPaymentStatus(String pgTxId);
}
