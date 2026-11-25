package com.subflow.pg;

import java.math.BigDecimal;

/**
 * PG 거래 상태 조회 결과(정합성 대사용).
 *
 * @param pgTxId PG 거래 ID
 * @param orderId 가맹점 주문 식별자
 * @param status  PG 거래 상태
 * @param amount  승인 금액
 * @param currency 통화
 */
public record PaymentStatusResult(
        String pgTxId,
        String orderId,
        PgTransactionStatus status,
        BigDecimal amount,
        String currency
) {

    public static PaymentStatusResult unknown(String pgTxId) {
        return new PaymentStatusResult(pgTxId, null, PgTransactionStatus.UNKNOWN, null, null);
    }
}
