package com.subflow.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 정합성 점검 결과.
 *
 * @param logId         저장된 점검 로그 ID
 * @param targetDate    점검 대상일(UTC)
 * @param executedAt    실행 시각
 * @param checkedCount  대사한 결제 건수
 * @param mismatchCount 발견한 불일치 건수
 * @param autoFixedCount 자동 보정한 건수
 * @param details       불일치 상세(사람이 읽을 설명)
 */
public record ReconciliationResult(Long logId, LocalDate targetDate, Instant executedAt,
                                   int checkedCount, int mismatchCount, int autoFixedCount,
                                   List<String> details) {

    public int unresolvedCount() {
        return mismatchCount - autoFixedCount;
    }
}
