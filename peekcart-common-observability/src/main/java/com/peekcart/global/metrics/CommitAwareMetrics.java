package com.peekcart.global.metrics;

import io.micrometer.core.instrument.Counter;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션이 <b>커밋된 뒤에만</b> 카운터를 올린다 (구현 ④-d-1 diff 리뷰 #1).
 *
 * <p><b>왜 필요한가</b>: saga 계측은 전부 {@code @Transactional} 메서드 안에서 일어난다.
 * 메서드 본문에서 바로 증가시키면 이후 flush/commit 이 실패했을 때 <b>DB·Outbox 는 롤백되는데
 * Micrometer 카운터만 남는다</b>. 그러면 메트릭이 실제 사건 수를 부풀리고, "실제 전이가 일어났을
 * 때만 올린다" 는 계약이 깨진다 — 그 계약이 alert 임계값의 근거다.
 *
 * <p>트랜잭션이 없으면(스케줄러 바깥 호출 등) 즉시 증가시킨다. 커밋을 기다릴 대상이 없기 때문이다.
 *
 * <p>롤백 시에는 {@link TransactionSynchronization#afterCommit()} 가 호출되지 않으므로 증가가
 * 일어나지 않는다 — 그게 이 클래스의 전부다.
 */
public final class CommitAwareMetrics {

    private CommitAwareMetrics() {
    }

    public static void increment(Counter counter) {
        increment(counter, 1.0);
    }

    public static void increment(Counter counter, double amount) {
        if (amount <= 0) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            counter.increment(amount);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                counter.increment(amount);
            }
        });
    }
}
