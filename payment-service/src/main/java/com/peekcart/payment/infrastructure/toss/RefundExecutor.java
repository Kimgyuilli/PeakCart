package com.peekcart.payment.infrastructure.toss;

import com.peekcart.payment.application.RefundOutcome;
import com.peekcart.payment.application.RefundProperties;
import com.peekcart.payment.domain.model.PaymentRefund;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * PG 취소 호출 + 조회 확정 실행기 (ADR-0018 D3/D5).
 *
 * <p>dispatcher 와 reconciliation 이 <b>같은 호출 규약</b>을 쓰도록 한 곳에 둔다 — 재호출 경로가
 * 다른 멱등키를 쓰면 PG 측 중복 방어가 무력해진다. 트랜잭션을 열지 않으며, 호출자가 claim(T1)과
 * 확정(T2) 사이에서 부른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundExecutor {

    private final TossPaymentClient tossPaymentClient;
    private final RefundProperties properties;

    /**
     * 신규 요청 실행 — 취소를 호출하고 결과를 확정한다.
     * "이미 취소됨"은 실패가 아니라 <b>조회로 판정할 입력</b>이므로 조회 분기로 넘긴다(D5).
     */
    public CallResult execute(PaymentRefund refund) {
        String key = idempotencyKey(refund.getOrderId());
        TossOutcome last = TossOutcome.unknown("호출 시도 없음");

        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            last = tossPaymentClient.cancel(refund.getPaymentKey(), "주문 보상 환불", key);
            switch (last.kind()) {
                case SUCCEEDED -> {
                    return new CallResult(RefundOutcome.succeeded(last.rawResponse()), attempt);
                }
                case PERMANENT_FAILURE -> {
                    return new CallResult(RefundOutcome.failed(last.code(), last.rawResponse()), attempt);
                }
                case ALREADY_CANCELED -> {
                    return new CallResult(verifyByQuery(refund), attempt);
                }
                default -> {
                    if (attempt < properties.getMaxAttempts()) {
                        sleepBackoff(attempt);
                    }
                }
            }
        }
        return new CallResult(RefundOutcome.unresolved(last.rawResponse()), properties.getMaxAttempts());
    }

    /**
     * 확정 실행 (reconciliation) — <b>조회를 먼저</b> 한다.
     *
     * <p>lease 가 만료된 {@code CLAIMED} 나 {@code UNRESOLVED} 는 "PG 를 불렀는지 알 수 없는" 상태다.
     * 조회 없이 재호출하면 crash matrix b 에서 <b>이미 성공한 취소를 다시 부른다</b>. 멱등키가 있어도
     * 상태 판정을 PG 응답에 의존하게 되므로, 진실을 먼저 확정하고 필요한 경우에만 호출한다.
     */
    public CallResult verifyThenExecute(PaymentRefund refund) {
        Optional<TossPaymentSnapshot> snapshot = tossPaymentClient.find(refund.getPaymentKey());
        if (snapshot.isEmpty()) {
            return new CallResult(RefundOutcome.unresolved("PG 조회 실패 — 진실 미확정"), 0);
        }
        TossPaymentSnapshot found = snapshot.get();
        if (found.isFullyCanceled(refund.getAmount())) {
            return new CallResult(RefundOutcome.succeeded(found.rawResponse()), 0);
        }
        if (found.canceledAmount() > 0) {
            // 부분 취소·금액 불일치는 본 계약 범위 밖(주문당 전액 1건) — 사람이 봐야 한다.
            return new CallResult(RefundOutcome.failed("AMOUNT_MISMATCH", found.rawResponse()), 0);
        }
        // 취소된 적 없음이 확정됐다 → 같은 멱등키로 재호출한다.
        return execute(refund);
    }

    /** ALREADY_CANCELED 응답의 진실을 조회로 가른다 (전액=성공 / 불일치=실패 / 조회불가=미확정). */
    private RefundOutcome verifyByQuery(PaymentRefund refund) {
        Optional<TossPaymentSnapshot> snapshot = tossPaymentClient.find(refund.getPaymentKey());
        if (snapshot.isEmpty()) {
            return RefundOutcome.unresolved("ALREADY_CANCELED 수신했으나 조회 실패 — 금액 미확인");
        }
        TossPaymentSnapshot found = snapshot.get();
        if (found.isFullyCanceled(refund.getAmount())) {
            return RefundOutcome.succeeded(found.rawResponse());
        }
        return RefundOutcome.failed("AMOUNT_MISMATCH", found.rawResponse());
    }

    /** 주문 단위 안정 멱등키 — 재시도·재실행·reconciliation 재호출에서 모두 동일하다. */
    public String idempotencyKey(Long orderId) {
        return "refund-" + orderId;
    }

    private void sleepBackoff(int attempt) {
        long millis = properties.getRetryBackoff().toMillis() * (1L << (attempt - 1));
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record CallResult(RefundOutcome outcome, int attempts) {
    }
}
