package com.subflow.domain.webhook;

/** 수신 웹훅 처리 상태. */
public enum WebhookInboxStatus {

    /** 수신됨. 처리 대기/진행. */
    RECEIVED,

    /** 처리 완료. */
    PROCESSED,

    /** 처리 실패(재처리 대상). */
    FAILED,

    /** 관심 없는 이벤트로 무시. */
    IGNORED;

    public boolean isTerminal() {
        return this == PROCESSED || this == IGNORED;
    }
}
