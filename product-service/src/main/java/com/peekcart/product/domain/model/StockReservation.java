package com.peekcart.product.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 재고 예약 원장 엔티티 (ADR-0012 D3, strangler-1).
 * orderId 단위 상태머신으로 비동기 예약/복구의 멱등·순서를 보장한다.
 */
@Entity
@Table(name = "stock_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(columnDefinition = "TEXT")
    private String items;

    @Column(name = "source_event_id", unique = true, length = 36)
    private String sourceEventId;

    @Column(name = "reserved_at")
    private LocalDateTime reservedAt;

    /**
     * 예약 lease 만료 시각 (계획 P4). Product 가 부여하고 {@code stock.reservation.result} 로 공유한다.
     * sweeper 는 이 시각(+유예) 을 근거로만 회수하며, null 이면 회수 대상이 아니다(기존 행 안전측).
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "compensated_at")
    private LocalDateTime compensatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private StockReservation(Long orderId, ReservationStatus status, String items, String sourceEventId,
                             Duration leaseTtl) {
        this.orderId = orderId;
        this.status = status;
        this.items = items;
        this.sourceEventId = sourceEventId;
        this.createdAt = LocalDateTime.now();
        if (status == ReservationStatus.RESERVED) {
            this.reservedAt = this.createdAt;
            this.expiresAt = this.createdAt.plus(leaseTtl);
        }
    }

    /**
     * 예약 성공 원장.
     *
     * @param leaseTtl 예약 유효기간. 만료 시각이 {@code stock.reservation.result} 로 공유된다
     */
    public static StockReservation reserved(Long orderId, String itemsJson, String sourceEventId, Duration leaseTtl) {
        return new StockReservation(orderId, ReservationStatus.RESERVED, itemsJson, sourceEventId, leaseTtl);
    }

    /** 재고 부족으로 실패한 원장. */
    public static StockReservation failed(Long orderId, String itemsJson, String sourceEventId) {
        return new StockReservation(orderId, ReservationStatus.FAILED, itemsJson, sourceEventId, Duration.ZERO);
    }

    /** 예약 도착 전 취소가 먼저 온 tombstone. */
    public static StockReservation cancelTombstone(Long orderId) {
        return new StockReservation(orderId, ReservationStatus.CANCEL_REQUESTED, null, null, Duration.ZERO);
    }
}
