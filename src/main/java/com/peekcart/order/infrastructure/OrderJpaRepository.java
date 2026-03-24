package com.peekcart.order.infrastructure;

import com.peekcart.order.domain.model.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByIdAndUserId(Long id, Long userId);
    Page<Order> findByUserId(Long userId, Pageable pageable);
}
