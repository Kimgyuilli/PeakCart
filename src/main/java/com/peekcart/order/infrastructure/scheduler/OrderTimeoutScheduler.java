package com.peekcart.order.infrastructure.scheduler;

import com.peekcart.order.application.OrderCommandService;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PAYMENT_REQUESTED 상태가 15분 초과된 주문을 자동 취소하는 스케줄러.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderTimeoutScheduler {

    private final OrderRepository orderRepository;
    private final OrderCommandService orderCommandService;

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "orderTimeoutCancelJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void cancelExpiredOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(15);
        List<Order> expiredOrders = orderRepository.findByStatusAndOrderedAtBefore(
                OrderStatus.PAYMENT_REQUESTED, cutoff);

        for (Order order : expiredOrders) {
            cancelSafely(order.getId(), order.getOrderNumber());
        }
    }

    private void cancelSafely(Long orderId, String orderNumber) {
        try {
            orderCommandService.cancelExpiredOrder(orderId);
            log.info("타임아웃 주문 취소: orderId={}, orderNumber={}", orderId, orderNumber);
        } catch (OrderException e) {
            log.warn("타임아웃 주문 취소 스킵 (상태 경합): orderId={}, reason={}", orderId, e.getMessage());
        } catch (Exception e) {
            log.error("타임아웃 주문 취소 실패: orderId={}", orderId, e);
        }
    }
}
