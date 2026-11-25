package com.subflow.domain.plan;

import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;

/**
 * 과금 주기.
 *
 * <p>월 단위 계산은 UTC 달력 기준이다(1/31 → 2/28처럼 말일 보정은 {@link java.time.ZonedDateTime#plus(Period)}가
 * 처리한다). 주 단위는 7일 고정 간격이다.
 */
public enum BillingCycle {

    WEEKLY("매주", Period.ofWeeks(1)),
    MONTHLY("매월", Period.ofMonths(1)),
    QUARTERLY("분기", Period.ofMonths(3)),
    YEARLY("매년", Period.ofYears(1));

    private final String label;
    private final Period period;

    BillingCycle(String label, Period period) {
        this.label = label;
        this.period = period;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 기준 시각에서 한 주기 뒤의 시각을 돌려준다.
     *
     * @param base 기준 시각 (UTC)
     * @return 다음 주기 시각
     */
    public Instant advance(Instant base) {
        return base.atZone(ZoneOffset.UTC).plus(period).toInstant();
    }
}
