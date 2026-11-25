package com.subflow.outbox;

/**
 * 웹훅 전송 추상화.
 *
 * <p>운영은 HTTP({@link HttpWebhookTransport}), 테스트는 기록형 구현으로 바꿔 끼운다.
 * 전송 실패는 예외 대신 {@link DeliveryResult} 로 돌려주어 발행기가 백오프 재시도를 결정하게 한다.
 */
public interface WebhookTransport {

    DeliveryResult send(WebhookDelivery delivery);

    /**
     * 전송 결과.
     *
     * @param success   2xx 여부
     * @param statusCode HTTP 상태 코드(전송 전 실패면 0)
     * @param error      실패 사유
     */
    record DeliveryResult(boolean success, int statusCode, String error) {

        public static DeliveryResult ok(int statusCode) {
            return new DeliveryResult(true, statusCode, null);
        }

        public static DeliveryResult failed(int statusCode, String error) {
            return new DeliveryResult(false, statusCode, error);
        }
    }
}
