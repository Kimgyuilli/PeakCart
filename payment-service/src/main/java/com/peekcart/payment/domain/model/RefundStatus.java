package com.peekcart.payment.domain.model;

/**
 * 환불 원장 상태 (ADR-0018 D2). 허용된 상태 전이 규칙을 직접 보유한다.
 *
 * <p>{@code UNRESOLVED} 는 <b>종결이 아니다</b> — reconciliation 이 PG 조회로 확정하거나(자동),
 * 상한을 넘기면 수동 종결로 {@code FAILED} 에 도달한다. 나가는 전이가 없는 상태로 두면
 * "미결로 남기지 않는다"(ADR-0012 D3 ④)가 깨진다.
 */
public enum RefundStatus {

    /** 요청 커밋 + fence 획득. 아직 PG 호출 전. */
    REQUESTED {
        @Override
        public boolean canTransitionTo(RefundStatus target) {
            return target == CLAIMED;
        }
    },

    /** dispatcher 가 소유권을 잡고 PG 호출 중. {@code claimed_at} 이 lease 기준이다. */
    CLAIMED {
        @Override
        public boolean canTransitionTo(RefundStatus target) {
            // CLAIMED → CLAIMED = stale claim 회수 후 재claim (lease 만료 시 reconciliation 이 수행)
            return target == SUCCEEDED || target == FAILED || target == UNRESOLVED || target == CLAIMED;
        }
    },

    /** PG 취소 성공 확정 (종결). */
    SUCCEEDED {
        @Override
        public boolean canTransitionTo(RefundStatus target) {
            return false;
        }
    },

    /** 영구 실패 확정 (종결). */
    FAILED {
        @Override
        public boolean canTransitionTo(RefundStatus target) {
            return false;
        }
    },

    /** 결과 불명 — 종결이 아니라 미해결. reconciliation 또는 수동 종결로만 벗어난다. */
    UNRESOLVED {
        @Override
        public boolean canTransitionTo(RefundStatus target) {
            // → CLAIMED: reconciliation 이 소유권을 잡는 전이. 이때 상태가 UNRESOLVED 로 남아 있으면
            //   진행 중인 건을 운영자가 수동 종결할 수 있다(실제 환불 성공 ↔ 원장 FAILED 불일치).
            return target == SUCCEEDED || target == FAILED || target == CLAIMED;
        }
    };

    public abstract boolean canTransitionTo(RefundStatus target);

    /** 더 이상 처리가 필요 없는 종결 상태인가. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
