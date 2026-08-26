package com.peekcart.global.deadletter;

/**
 * DLQ 원장 상태 (계획 ④-c-2a §2.6-A).
 *
 * <p><b>replay 관련 상태는 여기 없다.</b> {@code REPLAY_*}/{@code RESOLVED} 는 ④-c-2b 가
 * additive 로 추가한다 — "재발행 중복 0" 이 2단 상태머신과 outbox 재사용 양쪽에서 반증됐고
 * (`OutboxPollingService` 가 broker ack 후 별도로 상태를 저장한다), 그 표면은 ADR 사안이다.
 *
 * <p>그래서 DB 컬럼은 enum 제약이 아니라 {@code VARCHAR} 이고, 검증은 애플리케이션이 한다.
 * 2b 가 값을 추가할 때 마이그레이션이 필요 없게 하기 위함이다.
 */
public enum DeadLetterStatus {

    /** 적재 직후. 아직 사람이 보지 않았다. */
    OPEN,

    /** 운영자가 확인했다. 원인 조사 중이라는 뜻이며 해소를 뜻하지 않는다. */
    ACKED,

    /** 재처리하지 않기로 결정했다. 사유 기록이 필수다 — 근거 없는 종결을 만들지 않는다. */
    DISCARDED;

    /** 더 이상 전이하지 않는 상태인가. */
    public boolean isTerminal() {
        return this == DISCARDED;
    }
}
