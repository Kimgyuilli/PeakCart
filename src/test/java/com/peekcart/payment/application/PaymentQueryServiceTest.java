package com.peekcart.payment.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ServiceTest
@DisplayName("PaymentQueryService 단위 테스트")
class PaymentQueryServiceTest {

    @InjectMocks PaymentQueryService paymentQueryService;
    @Mock PaymentRepository paymentRepository;

    @Test
    @DisplayName("getPaymentByOrderId: 결제 정보를 반환한다")
    void getPaymentByOrderId_success() {
        given(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID))
                .willReturn(Optional.of(PaymentFixture.paymentWithId()));

        PaymentDetailDto result = paymentQueryService.getPaymentByOrderId(PaymentFixture.DEFAULT_ORDER_ID);

        assertThat(result.orderId()).isEqualTo(PaymentFixture.DEFAULT_ORDER_ID);
        assertThat(result.amount()).isEqualTo(PaymentFixture.DEFAULT_AMOUNT);
        assertThat(result.status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("getPaymentByOrderId: 결제 정보가 없으면 PAY_003 예외가 발생한다")
    void getPaymentByOrderId_notFound_throwsPAY003() {
        given(paymentRepository.findByOrderId(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentQueryService.getPaymentByOrderId(99L))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).getErrorCode()).isEqualTo(ErrorCode.PAY_003));
    }
}
