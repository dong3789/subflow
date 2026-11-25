package com.subflow.outbox;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * HTTP 웹훅 전송기.
 *
 * <p>짧은 타임아웃(3초)으로 보내고, 2xx 가 아니면 실패로 돌려준다.
 * 발행기는 실패한 이벤트를 지수 백오프로 다시 시도하므로, 여기서 재시도하지 않는다.
 */
@Component
@ConditionalOnProperty(name = "subflow.webhook.transport", havingValue = "http", matchIfMissing = true)
public class HttpWebhookTransport implements WebhookTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpWebhookTransport.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Override
    public DeliveryResult send(WebhookDelivery delivery) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(delivery.url()))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.body()));
            delivery.headers().forEach(builder::header);
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return DeliveryResult.ok(response.statusCode());
            }
            return DeliveryResult.failed(response.statusCode(),
                    "HTTP " + response.statusCode() + ": " + truncate(response.body(), 200));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("웹훅 전송 실패 url={}: {}", delivery.url(), e.getMessage());
            return DeliveryResult.failed(0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
