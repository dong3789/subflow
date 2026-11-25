package com.subflow.domain.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 일일 정합성 점검 결과 로그.
 *
 * <p>점검 항목(2단계 구현 예정):
 * <ul>
 *   <li>PG 정산 내역 ↔ {@code payments} 성공 건수/금액 대사</li>
 *   <li>청구 예정({@code next_billing_at}) 누락 구독 탐지</li>
 *   <li>성공 결제가 있는데 ACTIVE로 돌아오지 않은 구독 탐지</li>
 *   <li>PENDING에 오래 머문 결제·발행되지 않은 Outbox 이벤트 탐지</li>
 * </ul>
 * 자동 보정이 가능한 항목(상태 재계산 등)은 보정하고 {@code autoFixedCount}에 센다.
 */
@Entity
@Table(name = "reconciliation_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 점검 대상일(KST 기준). */
    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Column(name = "checked_count", nullable = false)
    private int checkedCount;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    @Column(name = "auto_fixed_count", nullable = false)
    private int autoFixedCount;

    /** 사람이 읽을 요약. 불일치 상세는 JSON 또는 줄바꿈 텍스트. */
    @Column(name = "summary")
    private String summary;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    private ReconciliationLog(LocalDate targetDate, int checkedCount, int mismatchCount,
                              int autoFixedCount, String summary, Instant executedAt) {
        this.targetDate = Objects.requireNonNull(targetDate, "targetDate");
        if (checkedCount < 0 || mismatchCount < 0 || autoFixedCount < 0) {
            throw new IllegalArgumentException("점검 건수는 0 이상이어야 합니다.");
        }
        if (autoFixedCount > mismatchCount) {
            throw new IllegalArgumentException("자동 보정 건수는 불일치 건수를 넘을 수 없습니다.");
        }
        this.checkedCount = checkedCount;
        this.mismatchCount = mismatchCount;
        this.autoFixedCount = autoFixedCount;
        this.summary = summary;
        this.executedAt = Objects.requireNonNull(executedAt, "executedAt");
    }

    public static ReconciliationLog record(LocalDate targetDate, int checkedCount, int mismatchCount,
                                           int autoFixedCount, String summary, Instant executedAt) {
        return new ReconciliationLog(targetDate, checkedCount, mismatchCount, autoFixedCount, summary, executedAt);
    }

    public boolean hasMismatch() {
        return mismatchCount > 0;
    }

    public int unresolvedCount() {
        return mismatchCount - autoFixedCount;
    }
}
