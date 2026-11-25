package com.subflow.domain.plan;

import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 과금 주기.
 *
 * <p>월 단위 계산은 UTC 달력 기준이다(1/31 → 2/28처럼 말일 보정은 {@link ZonedDateTime#plus(Period)}가
 * 처리한다). 주 단위는 고정 일수 간격이다.
 *
 * <p>2단계에서 {@link #BIWEEKLY}(격주), {@link #FOUR_WEEKLY}(4주)를 더했다. 4주 주기는 "매월 같은 요일"과
 * 다르므로 월 단위 기준일({@code anchorDay})을 쓰지 않는다.
 */
public enum BillingCycle {

    WEEKLY("매주", Period.ofWeeks(1)),
    BIWEEKLY("격주", Period.ofWeeks(2)),
    FOUR_WEEKLY("4주", Period.ofWeeks(4)),
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

    /** 달력 월 개념을 쓰는 주기인지(기준일 anchor day 를 유지하는 주기). */
    public boolean isMonthBased() {
        return this == MONTHLY || this == QUARTERLY || this == YEARLY;
    }

    /** 주 단위(7일 배수) 주기인지. */
    public boolean isWeekBased() {
        return this == WEEKLY || this == BIWEEKLY || this == FOUR_WEEKLY;
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

    /**
     * 주기 종류가 바뀔 때 기준일을 다시 계산한다.
     *
     * <ul>
     *   <li>월 단위로 바뀌면 기준일 = 적용 시점의 일(day of month)</li>
     *   <li>주 단위로 바뀌면 기준일 없음(null) — 매주 같은 요일·시각이 기준이 된다</li>
     * </ul>
     */
    public Integer anchorDayFor(Instant effectiveAt) {
        return isMonthBased() ? effectiveAt.atZone(ZoneOffset.UTC).getDayOfMonth() : null;
    }

    /** 체험 일수 계산에 쓰는 하루 단위 이동. */
    public Instant plusDays(Instant base, int days) {
        return base.plus(days, ChronoUnit.DAYS);
    }
}
