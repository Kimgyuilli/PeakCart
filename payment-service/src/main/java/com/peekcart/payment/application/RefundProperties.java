package com.peekcart.payment.application;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 환불 경로 동작 정책 (ADR-0018 · ADR-0007 base 소유).
 *
 * <p>연결 정보가 아니라 <b>동작 규약</b>이므로 프로파일이 아닌 base {@code application.yml} 이
 * 단독 소유한다. 값들 사이의 관계가 깨지면 부팅을 실패시킨다 — 예를 들어 claim lease 가 PG 호출
 * 소요보다 짧으면 살아있는 claim 을 다른 인스턴스가 회수해 <b>같은 결제에 두 번 취소를 시도</b>한다.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.refund")
public class RefundProperties {

    /** stale claim 판정 기준. 이 시간을 넘긴 CLAIMED 는 reconciliation 이 회수한다. */
    @NotNull
    private Duration claimLease;

    /**
     * PG 호출 1회의 타임아웃. connect·read 각각에 적용되므로 <b>최악은 이 값의 2배</b>다
     * (연결에 거의 다 쓰고 읽기에서 또 기다리는 경우) — lease 계산이 이를 반영한다.
     */
    @NotNull
    private Duration pgTimeout;

    /** PG 호출 최대 시도 횟수(첫 호출 포함). */
    @Min(1)
    private int maxAttempts;

    /** 재시도 백오프 초기 간격(지수 증가). */
    @NotNull
    private Duration retryBackoff;

    /** dispatcher/reconciliation 1회 실행당 조회 건수(배치 크기). */
    @Min(1)
    private int batchSize;

    /** 1회 실행당 최대 배치 수 — 한 배치가 계속 미확정이어도 다음 배치가 굶지 않게 한다. */
    @Min(1)
    private int maxBatchesPerRun;

    /** dispatcher 실행 간격(ms). {@code @Scheduled} placeholder 와 같은 키를 소유한다. */
    @Min(1000)
    private long dispatchIntervalMs;

    /** reconciliation 실행 간격(ms). */
    @Min(1000)
    private long reconcileIntervalMs;

    /** ShedLock lockAtMostFor. 한 배치의 최악 실행 시간보다 길어야 다중 인스턴스 겹침이 없다. */
    @NotNull
    private Duration lockAtMostFor;

    /** 결과 불명(UNRESOLVED) 상태를 자동 확정 시도하는 상한. 초과 시 수동 종결 대상. */
    @NotNull
    private Duration unresolvedLimit;

    /**
     * claim lease 는 PG 호출이 최악으로 걸리는 시간(타임아웃 × 시도 + 백오프)보다 길어야 한다.
     * 짧으면 살아있는 claim 이 회수돼 중복 취소 시도가 생긴다.
     */
    @AssertTrue(message = "app.refund.claim-lease 는 pg-timeout × max-attempts + 백오프 총합보다 길어야 합니다")
    public boolean isClaimLeaseLongerThanWorstCaseCall() {
        if (claimLease == null || pgTimeout == null || retryBackoff == null || maxAttempts < 1) {
            return true;   // @NotNull/@Min 이 먼저 보고하게 둔다
        }
        return claimLease.compareTo(worstCaseCall()) > 0;
    }

    /**
     * 건당 최악 호출 시간 = (connect + read) × 시도 + 지수 백오프 총합.
     * {@code pgTimeout} 이 connect·read 양쪽에 걸리므로 시도당 상한은 2배다 — 1배로 계산하면
     * lease 검증이 실제보다 낙관적이 돼 살아있는 호출이 회수될 수 있다.
     */
    private Duration worstCaseCall() {
        Duration backoffTotal = Duration.ZERO;
        for (int i = 1; i < maxAttempts; i++) {
            backoffTotal = backoffTotal.plus(retryBackoff.multipliedBy(1L << (i - 1)));
        }
        return pgTimeout.multipliedBy(2L * maxAttempts).plus(backoffTotal);
    }

    /**
     * ShedLock 시간은 <b>1회 실행 전체</b>의 최악 시간(건당 최악 호출 × batch-size × max-batches-per-run)
     * 보다 길어야 한다. 짧으면 락이 먼저 풀려 다른 인스턴스가 같은 잡에 진입한다 — per-row claim 이
     * 최종 안전망이지만, 락을 맞춰 두면 중복 외부 호출 시도 자체가 줄어든다.
     */
    @AssertTrue(message = "app.refund.lock-at-most-for 는 batch-size × max-batches-per-run × 건당 최악 호출 시간보다 길어야 합니다")
    public boolean isLockLongerThanWorstCaseRun() {
        if (lockAtMostFor == null || pgTimeout == null || retryBackoff == null
                || maxAttempts < 1 || batchSize < 1 || maxBatchesPerRun < 1) {
            return true;
        }
        // 스케줄러는 1회 실행에서 배치를 maxBatchesPerRun 번 반복한다 — 락은 그 전체를 덮어야 한다.
        Duration worstRun = worstCaseCall().multipliedBy((long) batchSize * maxBatchesPerRun);
        return lockAtMostFor.compareTo(worstRun) > 0;
    }

    /** 미해결 상한은 claim lease 보다 길어야 한다(회수 전에 수동 종결로 넘어가면 안 된다). */
    @AssertTrue(message = "app.refund.unresolved-limit 는 claim-lease 보다 길어야 합니다")
    public boolean isUnresolvedLimitLongerThanClaimLease() {
        if (unresolvedLimit == null || claimLease == null) {
            return true;
        }
        return unresolvedLimit.compareTo(claimLease) > 0;
    }
}
