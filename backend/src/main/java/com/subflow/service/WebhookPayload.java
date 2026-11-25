package com.subflow.service;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * PG 웹훅 본문.
 *
 * <p>실제 PG 마다 필드명이 달라 어댑터에서 이 모양으로 정규화한다.
 *
 * @param eventId   PG 이벤트 ID(재전송 시 동일 — 멱등 처리의 키)
 * @param eventType 이벤트 종류(PAYMENT_CANCELED, PAYMENT_REFUNDED 등)
 * @param pgTxId    대상 PG 거래 ID
 * @param amount    취소/환불 금액(있으면)
 * @param reason    사유
 */
public record WebhookPayload(
        @NotBlank String eventId,
        @NotBlank String eventType,
        String pgTxId,
        BigDecimal amount,
        String reason
) {
}
