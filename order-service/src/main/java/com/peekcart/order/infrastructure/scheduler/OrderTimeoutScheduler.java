package com.peekcart.order.infrastructure.scheduler;

import com.peekcart.order.application.OrderCommandService;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 타임아웃 주문을 자동 취소하는 스케줄러.
 * <ul>
 *   <li>PAYMENT_REQUESTED 가 15분 초과된 주문</li>
 *   <li>재고 예약 결과 미도착으로 확정되지 않은 채 5분 초과된 PENDING 주문 (예약 Saga 수렴, ADR-0012 D3)</li>
 *   <li>예약 lease 가 만료됐는데 결제를 시작하지 않은 PENDING 주문 (계획 P3/P4)</li>
 * </ul>
 *
 * <p>세 잡은 서로 겹치지 않는 구간을 담당한다. 특히 세 번째가 없으면 "예약은 확정됐으나 결제를
 * 시작하지 않은 PENDING" 주문에 수명 상한이 없어, Product 의 lease sweeper 가 살아있는 주문의
 * 재고를 회수하는 oversell 이 발생한다(계획 §2.3-A).
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
        List<Order> expiredOrders = orderRepository.findExpiredPaymentRequested(cutoff);

        for (Order order : expiredOrders) {
            cancelSafely(order.getId(), order.getOrderNumber());
        }
    }

    /**
     * 예약 미확정 PENDING 주문 수렴. 정상 예약 진행 중(확정됨) 주문은 제외되어 조기 취소되지 않는다.
     */
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "orderReservationTimeoutJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void cancelUnconfirmedReservations() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        List<Order> stuck = orderRepository.findUnconfirmedReservationBefore(cutoff);

        for (Order order : stuck) {
            cancelSafely(order.getId(), order.getOrderNumber());
        }
    }

    /**
     * 예약 lease 가 만료됐는데 결제를 시작하지 않은 PENDING 주문을 취소한다 (계획 P3/P4).
     * 만료 시각은 Product 가 {@code stock.reservation.result} 로 부여한 값이라 Order 가 독자적으로
     * 추측하지 않는다 — 양측이 같은 시각을 보는 것이 sweeper 와의 순서(Order 가 먼저)를 보장한다.
     */
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "orderReservationLeaseExpiryJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void cancelExpiredReservationLeases() {
        List<Order> expired = orderRepository.findExpiredReservationLease(LocalDateTime.now());

        for (Order order : expired) {
            cancelSafely(order.getId(), order.getOrderNumber());
        }
    }

    private void cancelSafely(Long orderId, String orderNumber) {
        try {
            orderCommandService.cancelExpiredOrder(orderId);
            log.info("타임아웃 주문 취소: orderId={}, orderNumber={}", orderId, orderNumber);
        } catch (OrderException e) {
            log.warn("타임아웃 주문 취소 스킵 (상태 경합): orderId={}, reason={}", orderId, e.getMessage());
        } catch (OptimisticLockingFailureException e) {
            // 낙관 락 충돌 정책(계획 P2): 취소는 재시도하지 않고 포기한다. 충돌 상대는 결제 완료 소비이며,
            // 결제가 이미 커밋됐다면 취소는 애초에 일어나선 안 되는 전이다. 다음 주기에 재평가된다.
            log.warn("타임아웃 주문 취소 포기 (동시 전이 충돌 — 결제 완료 우선): orderId={}", orderId);
        } catch (Exception e) {
            log.error("타임아웃 주문 취소 실패: orderId={}", orderId, e);
        }
    }
}
