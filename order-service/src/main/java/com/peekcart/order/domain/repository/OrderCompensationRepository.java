package com.peekcart.order.domain.repository;

import com.peekcart.order.domain.model.CompensationReason;
import com.peekcart.order.domain.model.OrderCompensation;

import java.util.Optional;

/**
 * 주문 보상 원장 리포지터리 (GW-2 #2).
 */
public interface OrderCompensationRepository {

    OrderCompensation save(OrderCompensation compensation);

    Optional<OrderCompensation> findByOrderIdAndReason(Long orderId, CompensationReason reason);
}
