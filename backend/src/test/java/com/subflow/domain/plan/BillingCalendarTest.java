package com.subflow.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 청구 달력(주기·기준일) 계산 단위 테스트.
 *
 * <p>2단계에서 사고가 나기 쉬운 부분이 "주기를 바꿨을 때 다음 결제일"이다.
 * 기간의 끝(한 주기 보장)과 다음 결제일(가장 가까운 기준일)을 구분해 검증한다.
 */
class BillingCalendarTest {

    private static final Instant NOV_25 = Instant.parse("2026-11-25T00:00:00Z");

    @Test
    @DisplayName("다음 결제일은 지금 이후 가장 가까운 기준일이다 — 12/5 에 기준일 25일이면 12/25")
    void nextChargeDateAlignsToAnchorWithinSameMonth() {
        Instant dec5 = Instant.parse("2026-12-05T00:00:00Z");

        assertThat(BillingCalendar.nextChargeDate(dec5, BillingCycle.MONTHLY, 25))
                .isEqualTo(Instant.parse("2026-12-25T00:00:00Z"));
    }

    @Test
    @DisplayName("기준일이 지난 뒤에는 다음 달 기준일로 넘어간다")
    void movesToNextMonthWhenAnchorPassed() {
        Instant dec26 = Instant.parse("2026-12-26T00:00:00Z");
        Instant dec25 = Instant.parse("2026-12-25T00:00:00Z");

        assertThat(BillingCalendar.nextChargeDate(dec26, BillingCycle.MONTHLY, 25))
                .isEqualTo(Instant.parse("2027-01-25T00:00:00Z"));
        assertThat(BillingCalendar.nextChargeDate(dec25, BillingCycle.MONTHLY, 25))
                .as("오늘이 기준일이면 다음 달로 넘어간다")
                .isEqualTo(Instant.parse("2027-01-25T00:00:00Z"));
    }

    @Test
    @DisplayName("31일 기준은 말일 보정된다 — 1/31 다음은 2/28(2026년)")
    void clampsToMonthEnd() {
        Instant jan31 = Instant.parse("2026-01-31T00:00:00Z");

        assertThat(BillingCalendar.nextCycleEnd(jan31, BillingCycle.MONTHLY, 31))
                .isEqualTo(Instant.parse("2026-02-28T00:00:00Z"));
        assertThat(BillingCalendar.nextChargeDate(jan31, BillingCycle.MONTHLY, 31))
                .isEqualTo(Instant.parse("2026-02-28T00:00:00Z"));
    }

    @Test
    @DisplayName("기간의 끝은 기준일이 남아 있어도 한 주기 뒤로 계산한다(조기 청구 방지)")
    void nextCycleEndAlwaysAdvancesAFullCycle() {
        Instant dec23 = Instant.parse("2026-12-23T00:00:00Z");

        assertThat(BillingCalendar.nextCycleEnd(dec23, BillingCycle.MONTHLY, 25))
                .as("기간 종료 12/23 → 다음 기간은 2027-01-25")
                .isEqualTo(Instant.parse("2027-01-25T00:00:00Z"));
    }

    @Test
    @DisplayName("주 단위 주기는 기준일을 쓰지 않고 고정 일수로 계산한다")
    void weeklyCyclesIgnoreAnchor() {
        assertThat(BillingCalendar.nextChargeDate(NOV_25, BillingCycle.WEEKLY, 25))
                .isEqualTo(Instant.parse("2026-12-02T00:00:00Z"));
        assertThat(BillingCalendar.nextCycleEnd(NOV_25, BillingCycle.BIWEEKLY, null))
                .isEqualTo(Instant.parse("2026-12-09T00:00:00Z"));
        assertThat(BillingCalendar.nextCycleEnd(NOV_25, BillingCycle.FOUR_WEEKLY, null))
                .isEqualTo(Instant.parse("2026-12-23T00:00:00Z"));
    }

    @Test
    @DisplayName("분기·연 단위는 주기만큼 이동한 뒤 기준일로 정렬한다")
    void quarterlyAndYearlyAlignAfterAdvance() {
        assertThat(BillingCalendar.nextCycleEnd(NOV_25, BillingCycle.QUARTERLY, 25))
                .isEqualTo(Instant.parse("2027-02-25T00:00:00Z"));
        assertThat(BillingCalendar.nextCycleEnd(NOV_25, BillingCycle.YEARLY, 25))
                .isEqualTo(Instant.parse("2027-11-25T00:00:00Z"));
    }

    @Test
    @DisplayName("주기 변경 미리보기: 즉시 적용은 지금부터, 다음 주기 적용은 현재 기간 종료부터 센다")
    void previewAfterCycleChange() {
        Instant now = Instant.parse("2026-12-02T00:00:00Z");
        Instant currentEnd = Instant.parse("2026-12-23T00:00:00Z");

        assertThat(BillingCalendar.previewAfterCycleChange(true, now, currentEnd,
                BillingCycle.MONTHLY, 2))
                .as("즉시 적용: 지금(12/2) 이후 가장 가까운 2일 → 2027-01-02")
                .isEqualTo(Instant.parse("2027-01-02T00:00:00Z"));

        assertThat(BillingCalendar.previewAfterCycleChange(false, now, currentEnd,
                BillingCycle.MONTHLY, 25))
                .as("다음 주기 적용: 기간 종료(12/23) 뒤 한 주기 → 2027-01-25")
                .isEqualTo(Instant.parse("2027-01-25T00:00:00Z"));
    }

    @Test
    @DisplayName("기준일은 월 단위 주기에서만 생긴다")
    void anchorDayExistsOnlyForMonthBasedCycles() {
        assertThat(BillingCycle.MONTHLY.anchorDayFor(NOV_25)).isEqualTo(25);
        assertThat(BillingCycle.YEARLY.anchorDayFor(NOV_25)).isEqualTo(25);
        assertThat(BillingCycle.WEEKLY.anchorDayFor(NOV_25)).isNull();
        assertThat(BillingCycle.FOUR_WEEKLY.anchorDayFor(NOV_25)).isNull();
    }

    @Test
    @DisplayName("기준일은 1~31만 허용한다")
    void validatesAnchorDay() {
        assertThat(BillingCalendar.requireValidAnchorDay(31)).isEqualTo(31);
        assertThatThrownBy(() -> BillingCalendar.requireValidAnchorDay(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillingCalendar.requireValidAnchorDay(32))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
