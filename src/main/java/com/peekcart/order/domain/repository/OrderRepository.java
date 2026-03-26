package com.peekcart.order.domain.repository;

import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 주문 도메인 리포지터리 인터페이스.
 */
public interface OrderRepository {
    Order save(Order order);
    Optional<Order> findById(Long id);
    Optional<Order> findByIdAndUserId(Long id, Long userId);
    Page<Order> findByUserId(Long userId, Pageable pageable);
    List<Order> findByStatusAndOrderedAtBefore(OrderStatus status, LocalDateTime cutoff);
}
