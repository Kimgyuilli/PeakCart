package com.peekcart.payment.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.domain.exception.PaymentException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 결제 애그리거트 루트. 상태 전이 및 금액 검증 로직을 직접 보유한다.
 * payments 테이블에 created_at만 존재하므로 BaseEntity를 상속하지 않는다.
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "payment_key", nullable = false, unique = true)
    private String paymentKey;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column
    private String method;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private Payment(Long orderId, long amount) {
        this.orderId = orderId;
        this.paymentKey = UUID.randomUUID().toString();
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public static Payment create(Long orderId, long amount) {
        return new Payment(orderId, amount);
    }

    /**
     * Toss가 발급한 실제 paymentKey로 교체한다.
     * PENDING 상태에서만 호출 가능하다.
     *
     * @throws PaymentException PENDING 상태가 아니면 {@code PAY-004}
     */
    public void assignPaymentKey(String paymentKey) {
        if (this.status != PaymentStatus.PENDING) {
            throw new PaymentException(ErrorCode.PAY_004);
        }
        this.paymentKey = paymentKey;
    }

    /**
     * 결제를 승인 상태로 전이한다.
     *
     * @throws PaymentException PENDING 상태가 아니면 {@code PAY-004}
     */
    public void approve(String method, LocalDateTime approvedAt) {
        if (!this.status.canTransitionTo(PaymentStatus.APPROVED)) {
            throw new PaymentException(ErrorCode.PAY_004);
        }
        this.status = PaymentStatus.APPROVED;
        this.method = method;
        this.approvedAt = approvedAt;
    }

    /**
     * 결제를 실패 상태로 전이한다.
     *
     * @throws PaymentException PENDING 상태가 아니면 {@code PAY-004}
     */
    public void fail() {
        if (!this.status.canTransitionTo(PaymentStatus.FAILED)) {
            throw new PaymentException(ErrorCode.PAY_004);
        }
        this.status = PaymentStatus.FAILED;
    }

    /**
     * 요청 금액이 결제 금액과 일치하는지 검증한다.
     *
     * @throws PaymentException 금액 불일치 시 {@code PAY-001}
     */
    public void validateAmount(long requestedAmount) {
        if (this.amount != requestedAmount) {
            throw new PaymentException(ErrorCode.PAY_001);
        }
    }
}
