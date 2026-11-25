package com.subflow.service;

/**
 * 구독 생성 요청.
 *
 * @param customerId   고객 ID
 * @param planId       플랜 ID (판매 중이어야 한다)
 * @param billingKeyId 빌링키 ID(체험 구독은 null 가능)
 * @param quantity     수량(좌석 수 등). null 이면 1
 */
public record CreateSubscriptionCommand(Long customerId, Long planId, Long billingKeyId, Integer quantity) {

    public int resolvedQuantity() {
        return quantity == null ? 1 : quantity;
    }
}
