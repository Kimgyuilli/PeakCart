package com.peekcart.order.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.dto.OrderDetailDto;
import com.peekcart.order.application.dto.OrderSummaryDto;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.application.dto.CursorSlice;
import com.peekcart.order.domain.model.OrderCursor;
import com.peekcart.order.domain.repository.OrderRepository;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.OrderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ServiceTest
@DisplayName("OrderQueryService 단위 테스트")
class OrderQueryServiceTest {

    @InjectMocks OrderQueryService orderQueryService;
    @Mock OrderRepository orderRepository;

    /**
     * ordered_at 은 유니크하지 않으므로 id 만 다른 주문들을 만든다 —
     * 절단/커서 생성이 tie-break 를 타는 경로를 그대로 재현한다.
     */
    private static List<Order> ordersWithIds(long... ids) {
        List<Order> orders = new ArrayList<>();
        for (long id : ids) {
            Order order = OrderFixture.order();
            ReflectionTestUtils.setField(order, "id", id);
            ReflectionTestUtils.setField(order, "orderedAt", LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_000));
            orders.add(order);
        }
        return orders;
    }

    @Test
    @DisplayName("getOrders: 커서가 없으면 첫 페이지를 조회한다")
    void getOrders_firstPage() {
        given(orderRepository.findFirstPage(eq(OrderFixture.DEFAULT_USER_ID), any(Pageable.class)))
                .willReturn(ordersWithIds(3L));

        CursorSlice<OrderSummaryDto> result =
                orderQueryService.getOrders(OrderFixture.DEFAULT_USER_ID, null, 10);

        assertThat(result.content()).hasSize(1);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
        verify(orderRepository, never()).findPageAfterCursor(any(), any(), any(), any());
    }

    @Test
    @DisplayName("getOrders: 커서가 있으면 그 위치 뒤를 조회한다")
    void getOrders_afterCursor() {
        LocalDateTime orderedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_000);
        OrderCursor cursor = new OrderCursor(orderedAt, 9L);
        given(orderRepository.findPageAfterCursor(
                eq(OrderFixture.DEFAULT_USER_ID), eq(orderedAt), eq(9L), any(Pageable.class)))
                .willReturn(ordersWithIds(8L));

        CursorSlice<OrderSummaryDto> result =
                orderQueryService.getOrders(OrderFixture.DEFAULT_USER_ID, cursor, 10);

        assertThat(result.content()).hasSize(1);
        verify(orderRepository, never()).findFirstPage(any(), any());
    }

    @Test
    @DisplayName("getOrders: size + 1 건을 읽고 초과분을 잘라 hasNext 를 판정한다")
    void getOrders_truncatesExtraRow() {
        // size=2 요청에 3건이 돌아온 상황 — 3번째는 '다음 페이지가 있다'는 신호일 뿐 응답에 넣지 않는다.
        given(orderRepository.findFirstPage(eq(OrderFixture.DEFAULT_USER_ID), any(Pageable.class)))
                .willReturn(ordersWithIds(5L, 4L, 3L));

        CursorSlice<OrderSummaryDto> result =
                orderQueryService.getOrders(OrderFixture.DEFAULT_USER_ID, null, 2);

        assertThat(result.content()).hasSize(2);
        assertThat(result.hasNext()).isTrue();
        // 커서는 잘라낸 초과분이 아니라 응답 마지막 행(id=4)에서 만들어져야 한다.
        assertThat(OrderCursor.decode(result.nextCursor()).id()).isEqualTo(4L);
    }

    @Test
    @DisplayName("getOrders: size + 1 을 요청한다 (초과분 없이는 hasNext 를 알 수 없다)")
    void getOrders_requestsOneExtraRow() {
        given(orderRepository.findFirstPage(eq(OrderFixture.DEFAULT_USER_ID), any(Pageable.class)))
                .willReturn(List.of());

        orderQueryService.getOrders(OrderFixture.DEFAULT_USER_ID, null, 20);

        verify(orderRepository).findFirstPage(OrderFixture.DEFAULT_USER_ID, PageRequest.of(0, 21));
    }

    @Test
    @DisplayName("getOrders: 정확히 size 건이면 hasNext 는 false 다")
    void getOrders_exactlySize_noNext() {
        given(orderRepository.findFirstPage(eq(OrderFixture.DEFAULT_USER_ID), any(Pageable.class)))
                .willReturn(ordersWithIds(5L, 4L));

        CursorSlice<OrderSummaryDto> result =
                orderQueryService.getOrders(OrderFixture.DEFAULT_USER_ID, null, 2);

        assertThat(result.content()).hasSize(2);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("getOrder: 주문 상세를 반환한다")
    void getOrder_success() {
        Order order = OrderFixture.orderWithId();
        given(orderRepository.findByIdAndUserId(OrderFixture.DEFAULT_ORDER_ID, OrderFixture.DEFAULT_USER_ID))
                .willReturn(Optional.of(order));

        OrderDetailDto result = orderQueryService.getOrder(OrderFixture.DEFAULT_USER_ID, OrderFixture.DEFAULT_ORDER_ID);

        assertThat(result.orderNumber()).isEqualTo(OrderFixture.DEFAULT_ORDER_NUMBER);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    @DisplayName("getOrder: 주문이 없으면 ORD-001 예외가 발생한다")
    void getOrder_notFound_throwsORD001() {
        given(orderRepository.findByIdAndUserId(99L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderQueryService.getOrder(1L, 99L))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_001);
    }
}
