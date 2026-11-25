package com.subflow.api;

import com.subflow.api.dto.ApiDtos.WebhookResponse;
import com.subflow.service.WebhookPayload;
import com.subflow.service.WebhookProcessResult;
import com.subflow.service.WebhookProcessStatus;
import com.subflow.service.WebhookProcessingService;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PG 웹훅 수신 API.
 *
 * <p>{@code (pg_provider, event_id)} 유니크로 멱등하게 적재하고, 전액 환불/취소 웹훅이면
 * 결제 원장 취소 + 구독 자동 해지 + {@code subscription.canceled} Outbox 발행 + 감사 로그까지 한 번에 처리한다.
 *
 * <p>이미 처리된 이벤트는 200 으로 조용히 넘긴다(재전송 안전). 처리 실패만 500 으로 돌려 PG 재전송을 유도한다.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private final WebhookProcessingService webhookProcessingService;

    public WebhookController(WebhookProcessingService webhookProcessingService) {
        this.webhookProcessingService = webhookProcessingService;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<WebhookResponse> receive(@PathVariable String provider,
                                                   @Valid @RequestBody WebhookPayload payload) {
        WebhookProcessResult result = webhookProcessingService.handle(provider, payload, Instant.now());
        WebhookResponse body = new WebhookResponse(result.status().name(), result.provider(),
                result.eventId(), result.message(), result.subscriptionId());
        if (result.status() == WebhookProcessStatus.FAILED) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
        return ResponseEntity.ok(body);
    }
}
