package com.peekcart.payment.application;

/**
 * 원장에 반영할 <b>확정 결과</b> (ADR-0018 D2/D5).
 *
 * <p>PG 응답 분류({@code TossOutcome})와 분리한 이유: `ALREADY_CANCELED` 같은 응답은 그 자체로
 * 결과가 아니라 <b>조회로 확정해야 하는 입력</b>이다. 이 타입은 조회까지 끝난 뒤의 결론만 담는다.
 *
 * @param kind   확정 결론
 * @param code   실패 사유 코드(실패일 때)
 * @param detail 감사용 상세(PG 응답 원문 또는 오류 메시지)
 */
public record RefundOutcome(Kind kind, String code, String detail) {

    public enum Kind {
        SUCCEEDED,
        FAILED,
        /** 확정 불가 — 종결이 아니라 미해결로 남긴다. */
        UNRESOLVED
    }

    public static RefundOutcome succeeded(String detail) {
        return new RefundOutcome(Kind.SUCCEEDED, null, detail);
    }

    public static RefundOutcome failed(String code, String detail) {
        return new RefundOutcome(Kind.FAILED, code, detail);
    }

    public static RefundOutcome unresolved(String detail) {
        return new RefundOutcome(Kind.UNRESOLVED, null, detail);
    }
}
