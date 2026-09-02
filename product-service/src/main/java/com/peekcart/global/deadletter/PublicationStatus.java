package com.peekcart.global.deadletter;

/**
 * DLQ 원장의 <b>발행 축</b> 상태 (ADR-0020 §D6-1).
 *
 * <p>컬럼은 nullable 이며 <b>{@code NULL} 이 "replay 를 요청한 적 없음"</b> 이다 — 원장 행 절대다수가 그 상태다.
 *
 * <p><b>어느 값도 terminal 이 아니다.</b> {@link #PUBLISHED} 여도 사건은 미결로 남고 backlog 에 계속 잡힌다
 * (§D6-2). 사건의 종결은 {@link DeadLetterStatus#RESOLVED}/{@link DeadLetterStatus#DISCARDED} 로만 이루어진다.
 *
 * <p>전이 주체는 <b>reconciler 1종</b>이다(§D6-4). 관리 API 는 {@link #REQUESTED} 까지만 만든다.
 */
public enum PublicationStatus {

    /** 관리 API 가 재발행을 요청했다. 아직 발행 여부를 모른다. 이 상태에서는 사건을 종결할 수 없다(I-1). */
    REQUESTED,

    /** broker ack 를 받았다. <b>사건 해소가 아니다.</b> */
    PUBLISHED,

    /** outbox 재시도가 소진됐다. 재요청이 허용된다. */
    PUBLISH_FAILED
}
