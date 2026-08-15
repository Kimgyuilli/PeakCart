package com.peekcart.payment.infrastructure.scheduler;

import com.peekcart.global.port.SlackPort;
import com.peekcart.payment.application.PaymentRefundService;
import com.peekcart.payment.application.RefundProperties;
import com.peekcart.payment.domain.model.PaymentRefund;
import com.peekcart.payment.infrastructure.toss.RefundExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 환불 결과 확정 잡 (ADR-0018 D3 crash matrix).
 *
 * <p>로컬 fence 는 동시성만 직렬화할 뿐 <b>외부 호출 경계의 crash 를 덮지 못한다</b>. 남는 관측 상태:
 * <ul>
 *   <li>(a) claim 커밋 후 PG 호출 전 사망 → {@code CLAIMED} lease 만료 → <b>dispatcher 가 재claim</b></li>
 *   <li>(b) PG 성공 후 확정 커밋 전 사망 → 동일. 재호출은 같은 멱등키라 이중 환불이 되지 않는다</li>
 *   <li>(c) 타임아웃·재시도 소진 → {@code UNRESOLVED} → <b>이 잡이 조회로 확정</b></li>
 * </ul>
 * 셋 다 <b>PG 조회로 진실을 확정</b>하는 것으로 수렴한다 — 그래서 조회는 선택이 아니라 필수다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundReconciliationScheduler {

    private final PaymentRefundService refundService;
    private final RefundExecutor refundExecutor;
    private final RefundProperties properties;
    private final SlackPort slackPort;

    @Scheduled(fixedDelayString = "${app.refund.reconcile-interval-ms}")
    @SchedulerLock(name = "refundReconcileJob",
            lockAtMostFor = "${app.refund.lock-at-most-for}", lockAtLeastFor = "PT30S")
    public void reconcile() {
        for (int batch = 0; batch < properties.getMaxBatchesPerRun(); batch++) {
            List<Long> candidates = refundService.findReconcileCandidates();
            if (candidates.isEmpty()) {
                return;
            }
            processBatch(candidates);
        }
    }

    private void processBatch(List<Long> candidates) {
        for (Long orderId : candidates) {
            try {
                reconcileOne(orderId);
            } catch (Exception e) {
                log.error("환불 확정 실패 — orderId={}", orderId, e);
            }
        }
    }

    private void reconcileOne(Long orderId) {
        // per-row claim: 한 인스턴스만 외부 호출한다(ShedLock 만료 시 배치 겹침 방지).
        Optional<PaymentRefund> claimed = refundService.claimForReconcile(orderId);
        if (claimed.isEmpty()) {
            return;
        }
        PaymentRefund refund = claimed.get();

        // 조회 선행 → 필요할 때만 동일 멱등키로 재호출 (ADR-0018 D3 crash matrix a/b)
        RefundExecutor.CallResult result = refundExecutor.verifyThenExecute(refund);
        refundService.finalizeOutcome(orderId, refund.getGeneration(), result.outcome(), result.attempts());

        refundService.find(orderId).ifPresent(this::escalateIfOverLimit);
    }

    /** 자동 확정 상한(정책값)을 넘긴 미해결은 운영 알림 + 수동 종결 대상으로 남긴다. */
    private void escalateIfOverLimit(PaymentRefund refund) {
        if (refund.isTerminal()) {
            return;
        }
        LocalDateTime limit = refund.getRequestedAt().plus(properties.getUnresolvedLimit());
        if (LocalDateTime.now().isBefore(limit)) {
            return;
        }
        log.error("환불 미해결 상한 초과 — 수동 종결 필요, orderId={}, requestedAt={}",
                refund.getOrderId(), refund.getRequestedAt());
        slackPort.send("[환불 미해결] orderId=" + refund.getOrderId()
                + " — 자동 확정 상한 초과. PG 조회 후 수동 종결 필요.");
    }
}
