package com.peekcart.order.infrastructure;

import com.peekcart.order.domain.model.CompensationReason;
import com.peekcart.order.domain.model.CompensationStatus;
import com.peekcart.order.domain.model.OrderCompensation;
import com.peekcart.order.domain.repository.OrderCompensationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link OrderCompensationRepository}의 JPA 구현체.
 */
@Repository
@RequiredArgsConstructor
public class OrderCompensationRepositoryImpl implements OrderCompensationRepository {

    private final OrderCompensationJpaRepository jpaRepository;

    @Override
    public OrderCompensation save(OrderCompensation compensation) {
        return jpaRepository.save(compensation);
    }

    @Override
    public Optional<OrderCompensation> findByOrderIdAndReason(Long orderId, CompensationReason reason) {
        return jpaRepository.findByOrderIdAndReason(orderId, reason);
    }

    @Override
    public long countByStatus(CompensationStatus status) {
        return jpaRepository.countByStatus(status);
    }
}
