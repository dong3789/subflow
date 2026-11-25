package com.subflow.outbox;

import java.util.Map;

/**
 * 발송할 웹훅 1건.
 *
 * @param url     수신 URL(프로젝트 webhook_url)
 * @param body    요청 본문(Outbox payload JSON)
 * @param headers 헤더(서명·이벤트 식별자 포함)
 */
public record WebhookDelivery(String url, String body, Map<String, String> headers) {

    public WebhookDelivery {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
