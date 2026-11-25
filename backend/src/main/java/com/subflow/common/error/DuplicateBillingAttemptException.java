package com.subflow.common.error;

import org.springframework.http.HttpStatus;

/**
 * 같은 구독에 대해 보호 윈도우 안에서 다시 청구하려 할 때(409).
 *
 * <p>당겨받기와 배치가 겹치거나, 주기 변경 직후 배치가 같은 기간을 다시 청구하는 사고를 막는다.
 */
public class DuplicateBillingAttemptException extends BusinessException {

    public DuplicateBillingAttemptException(String message) {
        super("DUPLICATE_BILLING_ATTEMPT", message, HttpStatus.CONFLICT);
    }
}
