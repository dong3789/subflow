package com.subflow.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BillingCycleTest {

    @Test
    @DisplayName("월 단위 주기는 말일을 보정해서 계산한다")
    void monthlyHandlesEndOfMonth() {
        assertThat(BillingCycle.MONTHLY.advance(Instant.parse("2026-01-31T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-02-28T00:00:00Z"));
        assertThat(BillingCycle.MONTHLY.advance(Instant.parse("2026-11-25T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-12-25T00:00:00Z"));
    }

    @Test
    @DisplayName("주·분기·연 주기를 계산한다")
    void otherCycles() {
        Instant base = Instant.parse("2026-11-25T00:00:00Z");

        assertThat(BillingCycle.WEEKLY.advance(base)).isEqualTo(Instant.parse("2026-12-02T00:00:00Z"));
        assertThat(BillingCycle.QUARTERLY.advance(base)).isEqualTo(Instant.parse("2027-02-25T00:00:00Z"));
        assertThat(BillingCycle.YEARLY.advance(base)).isEqualTo(Instant.parse("2027-11-25T00:00:00Z"));
    }

    @Test
    @DisplayName("윤년 2월 29일에서 1년을 더하면 2월 28일로 보정된다")
    void yearlyHandlesLeapDay() {
        assertThat(BillingCycle.YEARLY.advance(Instant.parse("2024-02-29T00:00:00Z")))
                .isEqualTo(Instant.parse("2025-02-28T00:00:00Z"));
    }
}
