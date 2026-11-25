package com.subflow.outbox;

import java.time.Instant;
import java.util.List;

/**
 * Outbox 발행 배치 결과.
 *
 * @param executedAt 발행 시각
 * @param published  발행 성공 건수
 * @param skipped    보낼 곳이 없어 건너뛴 건수(수신 URL 미등록)
 * @param retried    재시도 예약 건수
 * @param failed     재시도 한도 초과로 FAILED 확정된 건수
 * @param errors     실패 사유(운영 확인용)
 */
public record PublishBatchResult(Instant executedAt, int published, int skipped, int retried, int failed,
                                 List<String> errors) {

    public static PublishBatchResult of(Instant executedAt, int published, int skipped, int retried,
                                        int failed, List<String> errors) {
        return new PublishBatchResult(executedAt, published, skipped, retried, failed, List.copyOf(errors));
    }

    public int processed() {
        return published + skipped + retried + failed;
    }
}
