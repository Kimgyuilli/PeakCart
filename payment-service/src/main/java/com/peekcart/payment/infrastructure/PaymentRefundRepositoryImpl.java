package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.PaymentRefund;
import com.peekcart.payment.domain.model.RefundStatus;
import com.peekcart.payment.domain.repository.PaymentRefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentRefundRepositoryImpl implements PaymentRefundRepository {

    private final PaymentRefundJpaRepository jpaRepository;

    @Override
    public int insertRequestedIfAbsent(Long orderId, String paymentKey, Long userId, long amount) {
        // ON DUPLICATE KEY UPDATE id=id 는 충돌 시 0 을 돌려준다(MySQL: 신규 1, 변경 없음 0).
        return jpaRepository.insertRequestedIfAbsent(orderId, paymentKey, userId, amount, LocalDateTime.now());
    }

    @Override
    public int claimRequested(Long orderId, LocalDateTime now) {
        return jpaRepository.claimRequested(orderId, now);
    }

    @Override
    public int claimForReconcile(Long orderId, LocalDateTime staleBefore, LocalDateTime now) {
        return jpaRepository.claimForReconcile(orderId, staleBefore, now);
    }

    @Override
    public List<Long> findRequested(int limit) {
        return jpaRepository.findRequested(limit);
    }

    @Override
    public List<Long> findReconcileCandidates(LocalDateTime staleBefore, int limit) {
        return jpaRepository.findReconcileCandidates(staleBefore, limit);
    }

    @Override
    public Optional<PaymentRefund> findByOrderId(Long orderId) {
        return jpaRepository.findByOrderId(orderId);
    }

    @Override
    public Optional<PaymentRefund> findByOrderIdForUpdate(Long orderId) {
        return jpaRepository.findByOrderIdForUpdate(orderId);
    }

    @Override
    public long countByStatus(RefundStatus status) {
        return jpaRepository.countByStatus(status);
    }

    @Override
    public Optional<LocalDateTime> findOldestRequestedAt(RefundStatus status) {
        return jpaRepository.findOldestRequestedAt(status);
    }
}
