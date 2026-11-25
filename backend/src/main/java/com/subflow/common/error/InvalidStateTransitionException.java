package com.subflow.common.error;

import org.springframework.http.HttpStatus;

/**
 * 도메인 상태 머신이 허용하지 않는 전이를 시도했을 때(409).
 *
 * <p>예: 만료된 구독을 다시 활성화하려 하거나, 성공 처리된 결제를 실패로 바꾸려는 경우.
 */
public class InvalidStateTransitionException extends BusinessException {

    public InvalidStateTransitionException(String message) {
        super("INVALID_STATE_TRANSITION", message, HttpStatus.CONFLICT);
    }

    public static <S extends Enum<S>> InvalidStateTransitionException of(
            String subject, S current, S target) {
        return new InvalidStateTransitionException(
                "%s 상태를 %s에서 %s(으)로 바꿀 수 없습니다.".formatted(subject, current, target));
    }
}
