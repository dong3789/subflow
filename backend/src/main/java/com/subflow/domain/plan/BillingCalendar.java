package com.subflow.domain.plan;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * 청구 주기와 기준일(anchor day) 계산.
 *
 * <p>월 단위 주기는 "가입한 날(또는 변경한 날)의 일"을 기준일로 삼아 매달 같은 날 청구한다.
 * 말일 보정(31일 기준 → 2월은 28/29일)은 여기서 처리한다. 주 단위 주기는 기준일이 없다.
 *
 * <p>두 가지 계산을 구분한다:
 * <ul>
 *   <li>{@link #nextCycleEnd}: <b>이용 기간의 끝</b>. 기준 시점에서 정확히 한 주기 뒤를 기준일로 맞춘다.
 *       (결제 성공 후 다음 기간을 열 때 — 한 주기보다 짧아지지 않는다.)</li>
 *   <li>{@link #nextChargeDate}: <b>다음 결제일</b>. 지금 이후 가장 가까운 기준일을 고른다.
 *       (일시정지 해제·복구·결제일 변경·주기 즉시 변경처럼 "오늘부터 다시 센다"에 쓴다.)</li>
 * </ul>
 */
public final class BillingCalendar {

    private BillingCalendar() {
    }

    /**
     * 이용 기간의 끝(= 다음 청구 시각). 기준 시점에서 한 주기 뒤를 기준일로 맞춘다.
     *
     * @param base      기준 시각(UTC)
     * @param cycle     과금 주기
     * @param anchorDay 월 단위 기준일(1~31). null 이거나 주 단위 주기면 무시된다.
     */
    public static Instant nextCycleEnd(Instant base, BillingCycle cycle, Integer anchorDay) {
        Instant advanced = cycle.advance(base);
        if (anchorDay == null || !cycle.isMonthBased()) {
            return advanced;
        }
        ZonedDateTime target = advanced.atZone(ZoneOffset.UTC);
        int day = Math.min(anchorDay, target.toLocalDate().lengthOfMonth());
        return target.withDayOfMonth(day).toInstant();
    }

    /**
     * 다음 결제일. 지금 이후 가장 가까운 기준일을 고른다.
     *
     * <p>예: 12/5 에 일시정지 해제, 기준일 25일 → 12/25 (같은 달 기준일이 아직 남아 있으므로).
     * 기준일이 이미 지났거나 오늘이면 한 주기 뒤 기준일로 넘어간다.
     */
    public static Instant nextChargeDate(Instant base, BillingCycle cycle, Integer anchorDay) {
        if (anchorDay == null || !cycle.isMonthBased()) {
            return cycle.advance(base);
        }
        ZonedDateTime baseAt = base.atZone(ZoneOffset.UTC);
        ZonedDateTime sameMonth = baseAt.withDayOfMonth(
                Math.min(anchorDay, baseAt.toLocalDate().lengthOfMonth()));
        if (sameMonth.isAfter(baseAt)) {
            return sameMonth.toInstant();
        }
        return nextCycleEnd(base, cycle, anchorDay);
    }

    /**
     * 주기 변경 직후의 청구일 미리보기.
     *
     * @param immediate    {@code true} 면 지금부터, {@code false} 면 현재 기간 종료부터 센다
     * @param now          계산 기준 시각
     * @param currentEnd   현재 이용 기간 종료 시각
     * @param newCycle     바꿀 주기
     * @param newAnchorDay 바꿀 기준일
     */
    public static Instant previewAfterCycleChange(boolean immediate, Instant now, Instant currentEnd,
                                                  BillingCycle newCycle, Integer newAnchorDay) {
        if (immediate) {
            return nextChargeDate(now, newCycle, newAnchorDay);
        }
        Instant base = now.isAfter(currentEnd) ? now : currentEnd;
        return nextCycleEnd(base, newCycle, newAnchorDay);
    }

    /** 기준일을 1~31 범위로 검증한다. */
    public static int requireValidAnchorDay(int anchorDay) {
        if (anchorDay < 1 || anchorDay > 31) {
            throw new IllegalArgumentException("기준일은 1~31 사이여야 합니다: " + anchorDay);
        }
        return anchorDay;
    }
}
