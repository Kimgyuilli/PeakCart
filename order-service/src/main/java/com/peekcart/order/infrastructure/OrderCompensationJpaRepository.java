package com.peekcart.order.infrastructure;

import com.peekcart.order.domain.model.CompensationReason;
import com.peekcart.order.domain.model.CompensationStatus;
import com.peekcart.order.domain.model.OrderCompensation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * {@link OrderCompensation} 엔티티에 대한 Spring Data JPA 리포지터리.
 */
public interface OrderCompensationJpaRepository extends JpaRepository<OrderCompensation, Long> {

    Optional<OrderCompensation> findByOrderIdAndReason(Long orderId, CompensationReason reason);

    long countByStatus(CompensationStatus status);
}
