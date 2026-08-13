package com.peekcart.payment.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Payment 도메인 단위 테스트")
class PaymentTest {

    /** 마진과 무관한 게이트를 검증할 때 쓰는 값. 마진 자체의 검증은 LeaseApprovalMargin 참고. */
    private static final Duration NO_MARGIN = Duration.ZERO;

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("초기 상태가 PENDING이다")
        void initialStatusIsPending() {
            Payment payment = PaymentFixture.pendingPayment();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }

        @Test
        @DisplayName("orderId와 amount가 설정된다")
        void setsOrderIdAndAmount() {
            Payment payment = PaymentFixture.pendingPayment();
            assertThat(payment.getOrderId()).isEqualTo(PaymentFixture.DEFAULT_ORDER_ID);
            assertThat(payment.getAmount()).isEqualTo(PaymentFixture.DEFAULT_AMOUNT);
        }

        @Test
        @DisplayName("UUID 형식의 임시 paymentKey가 생성된다")
        void generatesUuidPaymentKey() {
            Payment payment = PaymentFixture.pendingPayment();
            assertThat(payment.getPaymentKey()).isNotBlank();
        }

        @Test
        @DisplayName("createdAt이 설정된다")
        void setsCreatedAt() {
            Payment payment = PaymentFixture.pendingPayment();
            assertThat(payment.getCreatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("assignPaymentKey")
    class AssignPaymentKey {

        @Test
        @DisplayName("PENDING 상태에서 paymentKey를 교체한다")
        void fromPending_success() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.assignPaymentKey("new-key");
            assertThat(payment.getPaymentKey()).isEqualTo("new-key");
        }

        @Test
        @DisplayName("APPROVED 상태에서 호출하면 PAY-004 예외가 발생한다")
        void fromApproved_throwsPAY004() {
            Payment payment = PaymentFixture.approvedPayment();

            assertThatThrownBy(() -> payment.assignPaymentKey("new-key"))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_004);
        }

        @Test
        @DisplayName("FAILED 상태에서 호출하면 PAY-004 예외가 발생한다")
        void fromFailed_throwsPAY004() {
            Payment payment = PaymentFixture.failedPayment();

            assertThatThrownBy(() -> payment.assignPaymentKey("new-key"))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_004);
        }
    }

    @Nested
    @DisplayName("approve")
    class Approve {

        @Test
        @DisplayName("PENDING 상태에서 승인하면 APPROVED가 된다")
        void fromPending_success() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.approve("카드", LocalDateTime.now());
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        }

        @Test
        @DisplayName("승인 시 method와 approvedAt이 설정된다")
        void setsMethodAndApprovedAt() {
            Payment payment = PaymentFixture.pendingPayment();
            LocalDateTime approvedAt = LocalDateTime.of(2026, 3, 25, 14, 0);
            payment.approve("카드", approvedAt);

            assertThat(payment.getMethod()).isEqualTo("카드");
            assertThat(payment.getApprovedAt()).isEqualTo(approvedAt);
        }

        @Test
        @DisplayName("APPROVED 상태에서 다시 승인하면 PAY-004 예외가 발생한다")
        void fromApproved_throwsPAY004() {
            Payment payment = PaymentFixture.approvedPayment();

            assertThatThrownBy(() -> payment.approve("카드", LocalDateTime.now()))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_004);
        }

        @Test
        @DisplayName("FAILED 상태에서 승인하면 PAY-004 예외가 발생한다")
        void fromFailed_throwsPAY004() {
            Payment payment = PaymentFixture.failedPayment();

            assertThatThrownBy(() -> payment.approve("카드", LocalDateTime.now()))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_004);
        }
    }

    @Nested
    @DisplayName("fail")
    class Fail {

        @Test
        @DisplayName("PENDING 상태에서 실패하면 FAILED가 된다")
        void fromPending_success() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.fail();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("APPROVED 상태에서 실패하면 PAY-004 예외가 발생한다")
        void fromApproved_throwsPAY004() {
            Payment payment = PaymentFixture.approvedPayment();

            assertThatThrownBy(payment::fail)
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_004);
        }

        @Test
        @DisplayName("FAILED 상태에서 다시 실패하면 PAY-004 예외가 발생한다")
        void fromFailed_throwsPAY004() {
            Payment payment = PaymentFixture.failedPayment();

            assertThatThrownBy(payment::fail)
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_004);
        }
    }

    @Nested
    @DisplayName("validateAmount")
    class ValidateAmount {

        @Test
        @DisplayName("금액이 일치하면 예외가 발생하지 않는다")
        void matchingAmount_noException() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.validateAmount(PaymentFixture.DEFAULT_AMOUNT);
        }

        @Test
        @DisplayName("금액이 불일치하면 PAY-001 예외가 발생한다")
        void mismatchAmount_throwsPAY001() {
            Payment payment = PaymentFixture.pendingPayment();

            assertThatThrownBy(() -> payment.validateAmount(99_999L))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_001);
        }
    }

    @Nested
    @DisplayName("verifyOwner (Seam 1 — payment-로컬 소유자 검증)")
    class VerifyOwner {

        @Test
        @DisplayName("소유자가 일치하면 예외가 발생하지 않는다")
        void matchingOwner_noException() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.verifyOwner(PaymentFixture.DEFAULT_USER_ID);
        }

        @Test
        @DisplayName("소유자가 불일치하면 PAY-007 예외가 발생한다")
        void mismatchOwner_throwsPAY007() {
            Payment payment = PaymentFixture.pendingPayment();

            assertThatThrownBy(() -> payment.verifyOwner(2L))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_007);
        }
    }

    @Nested
    @DisplayName("ensureConfirmable (reserve→pay + 취소 게이트)")
    class EnsureConfirmable {

        @Test
        @DisplayName("예약 확정(ready)된 PENDING 이면 통과한다")
        void readyPending_passes() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.markReadyForPayment(null);
            payment.ensureConfirmable(NO_MARGIN);
        }

        @Test
        @DisplayName("예약 미확정이면 PAY-008 예외가 발생한다")
        void notReady_throwsPAY008() {
            Payment payment = PaymentFixture.pendingPayment();

            assertThatThrownBy(() -> payment.ensureConfirmable(NO_MARGIN))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_008);
        }

        @Test
        @DisplayName("취소(CANCELLED)된 결제면 PAY-009 예외가 발생한다")
        void cancelled_throwsPAY009() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.markReadyForPayment(null);
            payment.cancelBeforePayment();

            assertThatThrownBy(() -> payment.ensureConfirmable(NO_MARGIN))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_009);
        }

        @Test
        @DisplayName("예약 lease 가 만료됐으면 PAY-010 — 회수된 재고에 과금하는 것을 막는다 (계획 P4)")
        void expiredLease_throwsPAY010() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.markReadyForPayment(LocalDateTime.now().minusSeconds(1));

            assertThatThrownBy(() -> payment.ensureConfirmable(NO_MARGIN))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_010);
        }

        @Test
        @DisplayName("lease 가 남아있으면 통과한다 (만료 게이트의 음성 대조)")
        void unexpiredLease_passes() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.markReadyForPayment(LocalDateTime.now().plusMinutes(30));

            payment.ensureConfirmable(NO_MARGIN);
        }

        @Test
        @DisplayName("lease 미수신(null)이면 만료 판정 없이 통과한다 (구 메시지 하위 호환)")
        void noLease_passes() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.markReadyForPayment(null);

            payment.ensureConfirmable(NO_MARGIN);
        }
    }

    @Nested
    @DisplayName("승인 마진 (GW-2 #1 — 경합 창 축소, fence 아님)")
    class LeaseApprovalMargin {

        private static final Duration MARGIN = Duration.ofMinutes(2);

        @Test
        @DisplayName("남은 lease 가 마진보다 짧으면 승인을 시작하지 않는다 (PAY-010)")
        void remainingLeaseShorterThanMargin_throwsPAY010() {
            Payment payment = PaymentFixture.pendingPayment();
            // 아직 만료 전이지만(1분 남음) 승인 소요(2분)를 덮지 못한다 → 시작 금지.
            payment.markReadyForPayment(LocalDateTime.now().plusMinutes(1));

            assertThatThrownBy(() -> payment.ensureConfirmable(MARGIN))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_010);
        }

        @Test
        @DisplayName("남은 lease 가 마진보다 길면 통과한다 (경계의 음성 대조)")
        void remainingLeaseLongerThanMargin_passes() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.markReadyForPayment(LocalDateTime.now().plusMinutes(3));

            payment.ensureConfirmable(MARGIN);
        }

        @Test
        @DisplayName("마진이 0이면 만료 직전에도 통과한다 — 마진이 실제로 창을 좁히는 변수임을 고정")
        void zeroMargin_passesRightBeforeExpiry() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.markReadyForPayment(LocalDateTime.now().plusMinutes(1));

            payment.ensureConfirmable(Duration.ZERO);

            // 같은 상태가 마진 2분에서는 거부된다 → 통과/거부를 가르는 것이 마진임이 대조로 드러난다.
            assertThatThrownBy(() -> payment.ensureConfirmable(MARGIN))
                    .isInstanceOf(PaymentException.class);
        }
    }

    @Nested
    @DisplayName("cancelBeforePayment (취소 게이트 + 상태머신 닫기)")
    class CancelBeforePayment {

        @Test
        @DisplayName("PENDING 이면 CANCELLED 로 종료하고 보상 불필요(false)")
        void fromPending_cancels() {
            Payment payment = PaymentFixture.pendingPayment();

            boolean compensationNeeded = payment.cancelBeforePayment();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(compensationNeeded).isFalse();
        }

        @Test
        @DisplayName("APPROVED 면 덮어쓰지 않고 보상 필요(true) 를 반환한다 (과금-후-취소)")
        void fromApproved_noOverwrite_needsCompensation() {
            Payment payment = PaymentFixture.approvedPayment();

            boolean compensationNeeded = payment.cancelBeforePayment();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(compensationNeeded).isTrue();
        }

        @Test
        @DisplayName("FAILED 면 no-op (덮어쓰지 않고 보상도 불필요)")
        void fromFailed_noop() {
            Payment payment = PaymentFixture.failedPayment();

            boolean compensationNeeded = payment.cancelBeforePayment();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(compensationNeeded).isFalse();
        }
    }
}
