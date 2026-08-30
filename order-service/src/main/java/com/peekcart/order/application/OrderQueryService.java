package com.peekcart.order.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.dto.CursorSlice;
import com.peekcart.order.application.dto.OrderDetailDto;
import com.peekcart.order.application.dto.OrderSummaryDto;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderCursor;
import com.peekcart.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 주문 조회를 담당하는 애플리케이션 서비스.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;

    /**
     * 사용자의 주문 목록을 커서 페이지네이션으로 조회한다.
     * orderItems에 접근하지 않아 N+1 쿼리가 발생하지 않는다.
     *
     * <p>입력 파싱·검증은 프레젠테이션이 끝낸 뒤라 여기서는 검증된 타입만 받는다.
     * {@code userId} 는 항상 인증 주체에서 오고, 커서는 위치 조건에만 쓰인다 —
     * 커서는 권한의 근거가 아니다.
     *
     * @param cursor 첫 페이지면 {@code null}
     */
    public CursorSlice<OrderSummaryDto> getOrders(Long userId, OrderCursor cursor, int size) {
        // size + 1 건을 읽어 초과분 유무로 hasNext 를 판정한다. COUNT 쿼리를 치지 않는 이유다.
        PageRequest limit = PageRequest.of(0, size + 1);
        List<Order> found = (cursor == null)
                ? orderRepository.findFirstPage(userId, limit)
                : orderRepository.findPageAfterCursor(userId, cursor.orderedAt(), cursor.id(), limit);

        boolean hasNext = found.size() > size;
        List<Order> page = hasNext ? found.subList(0, size) : found;

        String nextCursor = null;
        if (hasNext) {
            Order last = page.get(page.size() - 1);
            nextCursor = new OrderCursor(last.getOrderedAt(), last.getId()).encode();
        }
        return new CursorSlice<>(page.stream().map(OrderSummaryDto::from).toList(), nextCursor, hasNext);
    }

    /**
     * 주문 상세를 조회한다.
     *
     * @throws OrderException 주문이 없거나 본인 주문이 아니면 {@code ORD-001}
     */
    public OrderDetailDto getOrder(Long userId, Long orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .map(OrderDetailDto::from)
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
    }
}
