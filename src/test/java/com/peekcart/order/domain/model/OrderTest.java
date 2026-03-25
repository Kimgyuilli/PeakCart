package com.peekcart.order.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.support.fixture.OrderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Order 도메인 단위 테스트")
class OrderTest {

    @Test
    @DisplayName("create: 초기 상태가 PENDING이다")
    void create_initialStatusIsPending() {
        Order order = OrderFixture.order();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("create: totalAmount가 아이템 소계의 합으로 계산된다")
    void create_totalAmountCalculated() {
        List<OrderItemData> items = List.of(
                new OrderItemData(1L, 2, 10_000L),
                new OrderItemData(2L, 3, 5_000L)
        );
        Order order = Order.create(1L, "ORD-TEST", "홍길동", "010-0000-0000", "12345", "서울", items);

        assertThat(order.getTotalAmount()).isEqualTo(2 * 10_000L + 3 * 5_000L);
    }

    @Test
    @DisplayName("create: orderedAt이 설정된다")
    void create_orderedAtIsSet() {
        Order order = OrderFixture.order();
        assertThat(order.getOrderedAt()).isNotNull();
    }

    @Test
    @DisplayName("create: 빈 아이템 목록이면 ORD-004 예외가 발생한다")
    void create_emptyItems_throwsORD004() {
        assertThatThrownBy(() -> Order.create(1L, "ORD-TEST", "홍길동", "010", "12345", "서울", List.of()))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_004);
    }

    @Test
    @DisplayName("create: null 아이템 목록이면 ORD-004 예외가 발생한다")
    void create_nullItems_throwsORD004() {
        assertThatThrownBy(() -> Order.create(1L, "ORD-TEST", "홍길동", "010", "12345", "서울", null))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_004);
    }

    @Test
    @DisplayName("cancel: PENDING 상태에서 취소하면 CANCELLED가 된다")
    void cancel_fromPending_success() {
        Order order = OrderFixture.order();
        order.cancel();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancel: PAYMENT_REQUESTED 상태에서 취소가 가능하다")
    void cancel_fromPaymentRequested_success() {
        Order order = OrderFixture.order();
        order.transitionTo(OrderStatus.PAYMENT_REQUESTED);
        order.cancel();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancel: 이미 취소된 주문이면 ORD-002 예외가 발생한다")
    void cancel_alreadyCancelled_throwsORD002() {
        Order order = OrderFixture.order();
        order.cancel();

        assertThatThrownBy(order::cancel)
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_002);
    }

    @Test
    @DisplayName("cancel: DELIVERED 상태에서는 ORD-003 예외가 발생한다")
    void cancel_fromDelivered_throwsORD003() {
        Order order = OrderFixture.order();
        ReflectionTestUtils.setField(order, "status", OrderStatus.DELIVERED);

        assertThatThrownBy(order::cancel)
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_003);
    }

    @Test
    @DisplayName("transitionTo: 유효한 전이가 성공한다")
    void transitionTo_validTransition_success() {
        Order order = OrderFixture.order();
        order.transitionTo(OrderStatus.PAYMENT_REQUESTED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_REQUESTED);
    }

    @Test
    @DisplayName("transitionTo: 유효하지 않은 전이면 ORD-003 예외가 발생한다")
    void transitionTo_invalidTransition_throwsORD003() {
        Order order = OrderFixture.order();

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.DELIVERED))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_003);
    }
}
