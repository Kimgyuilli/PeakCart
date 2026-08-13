package com.peekcart.order.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 주문 보상 원장 (GW-2 #2). "보상이 필요하다"를 <b>영속</b> 사실로 남긴다.
 *
 * <p>알림(Slack)은 부가 신호일 뿐 종료 상태의 근거가 될 수 없다 — order-service 의 {@code SlackPort}
 * 는 배포 구성상 no-op 이고, 소비 트랜잭션이 커밋되면 {@code processed_events} 때문에 같은 이벤트를
 * 다시 소비할 수도 없다. 환불 요청 경로(계획 P8)는 이 원장을 입력으로 삼는다.
 */
@Entity
@Table(name = "order_compensations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderCompensation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private CompensationReason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompensationStatus status;

    @Column(length = 500)
    private String detail;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    private OrderCompensation(Long orderId, CompensationReason reason, String detail) {
        this.orderId = orderId;
        this.reason = reason;
        this.status = CompensationStatus.OPEN;
        this.detail = detail;
        this.detectedAt = LocalDateTime.now();
    }

    /** 미해소(OPEN) 보상 건을 생성한다. 해소(RESOLVED) 전이는 환불 요청 경로(P8) 소관이다. */
    public static OrderCompensation open(Long orderId, CompensationReason reason, String detail) {
        return new OrderCompensation(orderId, reason, detail);
    }
}
