package com.peekcart.payment.domain.model;

/**
 * 결제 상태 enum. 허용된 상태 전이 규칙을 직접 보유한다.
 */
public enum PaymentStatus {

    PENDING {
        @Override
        public boolean canTransitionTo(PaymentStatus target) {
            return target == APPROVED || target == FAILED;
        }
    },
    APPROVED {
        @Override
        public boolean canTransitionTo(PaymentStatus target) {
            return false;
        }
    },
    FAILED {
        @Override
        public boolean canTransitionTo(PaymentStatus target) {
            return false;
        }
    };

    public abstract boolean canTransitionTo(PaymentStatus target);
}
