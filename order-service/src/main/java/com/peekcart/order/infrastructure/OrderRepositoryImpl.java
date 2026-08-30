package com.peekcart.order.infrastructure;

import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link OrderRepository}의 JPA 구현체.
 */
@Repository
@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository orderJpaRepository;

    @Override
    public Order save(Order order) {
        return orderJpaRepository.save(order);
    }

    @Override
    public Optional<Order> findById(Long id) {
        return orderJpaRepository.findById(id);
    }

    @Override
    public Optional<Order> findByIdAndUserId(Long id, Long userId) {
        return orderJpaRepository.findByIdAndUserId(id, userId);
    }

    @Override
    public List<Order> findFirstPage(Long userId, Pageable limit) {
        return orderJpaRepository.findFirstPageByUserId(userId, limit);
    }

    @Override
    public List<Order> findPageAfterCursor(Long userId, LocalDateTime orderedAt, Long id, Pageable limit) {
        return orderJpaRepository.findPageByUserIdAfterCursor(userId, orderedAt, id, limit);
    }

    @Override
    public List<Order> findExpiredPaymentRequested(LocalDateTime cutoff) {
        return orderJpaRepository.findExpiredPaymentRequested(cutoff);
    }

    @Override
    public List<Order> findUnconfirmedReservationBefore(LocalDateTime cutoff) {
        return orderJpaRepository.findUnconfirmedReservationBefore(cutoff);
    }

    @Override
    public List<Order> findExpiredReservationLease(LocalDateTime now) {
        return orderJpaRepository.findExpiredReservationLease(now);
    }
}
