package com.peekcart.payment.infrastructure.toss;

/**
 * PG 결제 조회 스냅샷 (ADR-0018 D3 — crash 경계에서 "진실"을 확정하는 근거).
 *
 * @param status         PG 측 결제 상태
 * @param canceledAmount 지금까지 취소된 총액. 전액과 같으면 환불이 이미 성립한 것이다
 * @param rawResponse    응답 원문(감사)
 */
public record TossPaymentSnapshot(String status, long canceledAmount, String rawResponse) {

    /**
     * 요청 금액과 <b>정확히 같은</b> 금액이 취소됐는가.
     *
     * <p>{@code >=} 로 두면 원장 금액보다 큰 취소 합계(다른 결제와의 혼선·PG 데이터 이상)도 성공으로
     * 확정된다. 외부 응답은 신뢰 경계 밖이므로 정확 일치만 성공으로 본다 — 부분 취소와 초과 취소는
     * 둘 다 금액 불일치 실패로 간다(주문당 전액 1건, ADR-0018 D2).
     */
    public boolean isFullyCanceled(long amount) {
        return canceledAmount == amount;
    }
}
