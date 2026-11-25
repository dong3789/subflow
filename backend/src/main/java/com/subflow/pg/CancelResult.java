package com.subflow.pg;

/**
 * PG 취소/환불 결과.
 *
 * @param canceled 취소 성공 여부
 * @param pgTxId   대상 거래 ID
 * @param code     실패 코드(성공이면 null)
 * @param reason   실패 사유
 */
public record CancelResult(boolean canceled, String pgTxId, String code, String reason) {

    public static CancelResult success(String pgTxId) {
        return new CancelResult(true, pgTxId, null, null);
    }

    public static CancelResult failed(String pgTxId, String code, String reason) {
        return new CancelResult(false, pgTxId, code, reason);
    }
}
