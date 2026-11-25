package com.subflow.pg;

import java.math.BigDecimal;
import java.util.Map;

/**
 * PG 승인 요청.
 *
 * @param billingKeyToken PG 가 발급한 빌링키(카드 원본 아님)
 * @param amount          청구 금액
 * @param currency        ISO 4217 통화
 * @param orderId         가맹점 주문 식별자(구독·회차 기반)
 * @param idempotencyKey  중복 승인을 막는 멱등키(PG 는 같은 키를 같은 결제로 본다)
 * @param metadata        부가 정보(구독 식별자 등). 카드 정보는 담지 않는다.
 */
public record ChargeRequest(
        String billingKeyToken,
        BigDecimal amount,
        String currency,
        String orderId,
        String idempotencyKey,
        Map<String, String> metadata
) {

    public ChargeRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
