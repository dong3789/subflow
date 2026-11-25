package com.subflow.service;

import java.time.Instant;

/**
 * 주기 변경 미리보기 — 실제 변경 전에 다음 결제일이 어떻게 바뀌는지 보여 준다.
 *
 * @param subscriptionId      구독 ID
 * @param currentCycle        현재 주기
 * @param newCycle            바꿀 주기
 * @param currentAnchorDay    현재 기준일(월 단위가 아니면 null)
 * @param newAnchorDay        바뀔 기준일(월 단위가 아니면 null)
 * @param currentNextBillingAt 현재 다음 청구일
 * @param previewNextBillingAt 변경 후 다음 청구일
 * @param immediate           즉시 적용 여부
 */
public record CycleChangePreview(
        Long subscriptionId,
        String currentCycle,
        String newCycle,
        Integer currentAnchorDay,
        Integer newAnchorDay,
        Instant currentNextBillingAt,
        Instant previewNextBillingAt,
        boolean immediate
) {
}
