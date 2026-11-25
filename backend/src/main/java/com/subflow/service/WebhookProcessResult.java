package com.subflow.service;

/**
 * 웹훅 처리 결과.
 *
 * @param provider       PG 식별자
 * @param eventId        PG 이벤트 ID
 * @param status         처리 상태
 * @param message        사람이 읽을 설명
 * @param subscriptionId 함께 해지된 구독 ID(없으면 null)
 */
public record WebhookProcessResult(String provider, String eventId, WebhookProcessStatus status,
                                   String message, Long subscriptionId) {

    public boolean failed() {
        return status == WebhookProcessStatus.FAILED;
    }
}
