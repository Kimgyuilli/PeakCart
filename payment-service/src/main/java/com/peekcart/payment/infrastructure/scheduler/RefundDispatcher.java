package com.peekcart.payment.infrastructure.scheduler;

import com.peekcart.payment.application.PaymentRefundService;
import com.peekcart.payment.application.RefundProperties;
import com.peekcart.payment.domain.model.PaymentRefund;
import com.peekcart.payment.infrastructure.toss.RefundExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 환불 실행자 (ADR-0018 D3). <b>claim 한 건만 PG 로 보낸다.</b>
 *
 * <p>진입점(consumer·로컬 감지)은 {@code REQUESTED} 만 커밋하고 끝난다. 소비 트랜잭션 안에서
 * 외부를 호출하면 (a) 트랜잭션이 외부 지연만큼 길어지고 (b) <b>PG 성공 후 로컬 롤백 시 fence 행이
 * 사라져 다음 트리거가 다시 호출</b>한다 — fence 가 무력화되는 경로다.
 *
 * <p>이 클래스는 트랜잭션을 열지 않는다. claim(T1)·확정(T2)은 {@link PaymentRefundService} 의
 * 독립 트랜잭션이며 PG 호출은 <b>그 사이</b>에서 일어난다.
 *
 * <p><b>신규 {@code REQUESTED} 만</b> 처리한다 — lease 가 만료된 {@code CLAIMED} 는 PG 를 불렀는지
 * 알 수 없어 조회가 선행돼야 하므로 {@link RefundReconciliationScheduler} 소관이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundDispatcher {

    private final PaymentRefundService refundService;
    private final RefundExecutor refundExecutor;
    private final RefundProperties properties;

    @Scheduled(fixedDelayString = "${app.refund.dispatch-interval-ms}")
    @SchedulerLock(name = "refundDispatchJob",
            lockAtMostFor = "${app.refund.lock-at-most-for}", lockAtLeastFor = "PT10S")
    public void dispatch() {
        for (int batch = 0; batch < properties.getMaxBatchesPerRun(); batch++) {
            List<Long> candidates = refundService.findRequested();
            if (candidates.isEmpty()) {
                return;
            }
            processBatch(candidates);
        }
    }

    private void processBatch(List<Long> candidates) {
        for (Long orderId : candidates) {
            try {
                dispatchOne(orderId);
            } catch (Exception e) {
                // 한 건의 실패가 배치를 멈추지 않게 한다. 원장은 CLAIMED 로 남아 lease 회수 대상이 된다.
                log.error("환불 처리 실패 — orderId={}", orderId, e);
            }
        }
    }

    private void dispatchOne(Long orderId) {
        Optional<PaymentRefund> claimed = refundService.claimRequested(orderId);   // T1 (커밋됨)
        if (claimed.isEmpty()) {
            return;   // 다른 인스턴스가 가져감
        }
        PaymentRefund refund = claimed.get();
        RefundExecutor.CallResult result = refundExecutor.execute(refund);   // 트랜잭션 밖
        // claim 당시 generation 을 넘겨 lease 만료 후 소유권이 넘어갔다면 확정이 무시되게 한다.
        refundService.finalizeOutcome(orderId, refund.getGeneration(), result.outcome(), result.attempts());
    }
}
