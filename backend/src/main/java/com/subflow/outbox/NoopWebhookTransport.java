package com.subflow.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 전송하지 않는 웹훅 구현({@code subflow.webhook.transport=noop}).
 *
 * <p>받을 곳이 없는 로컬 데모에서 이벤트를 PUBLISHED 로 흘려보내 발행 대기 큐가 쌓이지 않게 한다.
 */
@Component
@ConditionalOnProperty(name = "subflow.webhook.transport", havingValue = "noop")
public class NoopWebhookTransport implements WebhookTransport {

    @Override
    public DeliveryResult send(WebhookDelivery delivery) {
        return DeliveryResult.ok(204);
    }
}
