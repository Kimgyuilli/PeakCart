package com.peekcart.order.infrastructure.scheduler;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.OrderCommandService;
import com.peekcart.order.infrastructure.metrics.OrderSagaMetrics;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.repository.OrderRepository;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.OrderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ServiceTest
@DisplayName("OrderTimeoutScheduler 단위 테스트")
class OrderTimeoutSchedulerTest {

    @InjectMocks OrderTimeoutScheduler orderTimeoutScheduler;
    @Mock OrderRepository orderRepository;
    @Mock OrderCommandService orderCommandService;
    // 취소 건수를 사유별로 세는 메트릭(구현 ④-d-1 P2). 여기서는 스케줄러 로직만 보므로 mock 이면 충분하다.
    @Mock OrderSagaMetrics sagaMetrics;

    @Test
    @DisplayName("만료 주문이 있으면 건별로 cancelExpiredOrder를 호출한다")
    void cancelExpiredOrders_withExpiredOrders() {
        Order order1 = OrderFixture.paymentRequestedOrderWithId();
        Order order2 = OrderFixture.paymentRequestedOrderWithId();
        ReflectionTestUtils.setField(order2, "id", 2L);
        given(orderRepository.findExpiredPaymentRequested(any(LocalDateTime.class)))
                .willReturn(List.of(order1, order2));

        orderTimeoutScheduler.cancelExpiredOrders();

        then(orderCommandService).should().cancelExpiredOrder(order1.getId());
        then(orderCommandService).should().cancelExpiredOrder(order2.getId());
    }

    @Test
    @DisplayName("만료 주문이 없으면 cancelExpiredOrder를 호출하지 않는다")
    void cancelExpiredOrders_noExpiredOrders() {
        given(orderRepository.findExpiredPaymentRequested(any(LocalDateTime.class)))
                .willReturn(List.of());

        orderTimeoutScheduler.cancelExpiredOrders();

        then(orderCommandService).should(never()).cancelExpiredOrder(any());
    }

    @Test
    @DisplayName("한 건 실패해도 나머지는 처리된다")
    void cancelExpiredOrders_oneFailsOthersContinue() {
        Order order1 = OrderFixture.paymentRequestedOrderWithId();
        Order order2 = OrderFixture.paymentRequestedOrderWithId();
        ReflectionTestUtils.setField(order2, "id", 2L);
        given(orderRepository.findExpiredPaymentRequested(any(LocalDateTime.class)))
                .willReturn(List.of(order1, order2));
        willThrow(new RuntimeException("DB error")).given(orderCommandService).cancelExpiredOrder(order1.getId());

        orderTimeoutScheduler.cancelExpiredOrders();

        then(orderCommandService).should(times(1)).cancelExpiredOrder(order1.getId());
        then(orderCommandService).should(times(1)).cancelExpiredOrder(order2.getId());
    }

    @Test
    @DisplayName("상태 경합(OrderException)이 발생해도 나머지는 처리된다")
    void cancelExpiredOrders_orderExceptionSkipsAndContinues() {
        Order order1 = OrderFixture.paymentRequestedOrderWithId();
        Order order2 = OrderFixture.paymentRequestedOrderWithId();
        ReflectionTestUtils.setField(order2, "id", 2L);
        given(orderRepository.findExpiredPaymentRequested(any(LocalDateTime.class)))
                .willReturn(List.of(order1, order2));
        willThrow(new OrderException(ErrorCode.ORD_003)).given(orderCommandService).cancelExpiredOrder(order1.getId());

        orderTimeoutScheduler.cancelExpiredOrders();

        then(orderCommandService).should(times(1)).cancelExpiredOrder(order1.getId());
        then(orderCommandService).should(times(1)).cancelExpiredOrder(order2.getId());
    }

    @Test
    @DisplayName("낙관 락 충돌은 재시도하지 않고 포기한다 — 결제 완료가 이긴 경우다 (계획 P2 충돌 정책)")
    void cancelExpiredOrders_optimisticLockConflictGivesUp() {
        Order order1 = OrderFixture.paymentRequestedOrderWithId();
        Order order2 = OrderFixture.paymentRequestedOrderWithId();
        ReflectionTestUtils.setField(order2, "id", 2L);
        given(orderRepository.findExpiredPaymentRequested(any(LocalDateTime.class)))
                .willReturn(List.of(order1, order2));
        willThrow(new OptimisticLockingFailureException("version conflict"))
                .given(orderCommandService).cancelExpiredOrder(order1.getId());

        orderTimeoutScheduler.cancelExpiredOrders();

        // 충돌 건은 1회만 호출(재시도 없음)하고, 잡 전체는 계속 진행한다.
        then(orderCommandService).should(times(1)).cancelExpiredOrder(order1.getId());
        then(orderCommandService).should(times(1)).cancelExpiredOrder(order2.getId());
    }

    @Test
    @DisplayName("lease 만료 잡: 만료된 예약 주문을 건별로 취소한다 (계획 P3)")
    void cancelExpiredReservationLeases_cancelsEach() {
        Order order1 = OrderFixture.orderWithId();
        Order order2 = OrderFixture.orderWithId();
        ReflectionTestUtils.setField(order2, "id", 2L);
        given(orderRepository.findExpiredReservationLease(any(LocalDateTime.class)))
                .willReturn(List.of(order1, order2));

        orderTimeoutScheduler.cancelExpiredReservationLeases();

        then(orderCommandService).should().cancelExpiredOrder(order1.getId());
        then(orderCommandService).should().cancelExpiredOrder(order2.getId());
    }

    @Test
    @DisplayName("lease 만료 잡: 대상이 없으면 아무것도 취소하지 않는다")
    void cancelExpiredReservationLeases_noneExpired() {
        given(orderRepository.findExpiredReservationLease(any(LocalDateTime.class)))
                .willReturn(List.of());

        orderTimeoutScheduler.cancelExpiredReservationLeases();

        then(orderCommandService).should(never()).cancelExpiredOrder(any());
    }
}
