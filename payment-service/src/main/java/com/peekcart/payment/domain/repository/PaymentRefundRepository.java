package com.peekcart.payment.domain.repository;

import com.peekcart.payment.domain.model.PaymentRefund;
import com.peekcart.payment.domain.model.RefundStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 환불 원장 repository (ADR-0018 D2/D3).
 *
 * <p>fence 획득과 claim 은 <b>원자 쿼리</b>로만 제공한다 — 조회 후 판단하는 API 를 두면
 * 동시 진입에서 둘 다 통과하는 창이 생긴다({@code PLAN-BLINDSPOTS.md} B12).
 *
 * <p>claim 이 두 종류인 이유: 신규 {@code REQUESTED} 는 "아직 PG 를 부른 적 없음"이 확실하지만,
 * lease 가 만료된 {@code CLAIMED} 는 <b>불렀는지 알 수 없는</b> 상태라 재호출 전에 조회가 필요하다.
 * 같은 경로로 처리하면 crash matrix b 에서 중복 호출이 된다.
 */
public interface PaymentRefundRepository {

    /**
     * 환불 요청을 fence 와 함께 생성한다 (단일 원자 INSERT).
     *
     * @return 1 = 이번 호출이 fence 를 획득(신규 생성) · 0 = 이미 존재(정상 no-op)
     */
    int insertRequestedIfAbsent(Long orderId, String paymentKey, Long userId, long amount);

    /** 신규 요청 claim (REQUESTED 전용, generation 증가). @return 1 = 획득 */
    int claimRequested(Long orderId, LocalDateTime now);

    /** 확정 대상 claim (lease 만료 CLAIMED · UNRESOLVED, generation 증가). @return 1 = 획득 */
    int claimForReconcile(Long orderId, LocalDateTime staleBefore, LocalDateTime now);

    /** dispatcher 후보 — 신규 요청만. */
    List<Long> findRequested(int limit);

    /** reconciliation 후보 — lease 만료 CLAIMED + UNRESOLVED. */
    List<Long> findReconcileCandidates(LocalDateTime staleBefore, int limit);

    Optional<PaymentRefund> findByOrderId(Long orderId);

    /** 확정용 조회 — 행 잠금과 함께 읽어 generation 검사의 TOCTOU 창을 없앤다. */
    Optional<PaymentRefund> findByOrderIdForUpdate(Long orderId);

    long countByStatus(RefundStatus status);

    /** 해당 상태의 가장 오래된 요청 시각 (미해결 backlog age 게이지용). */
    Optional<LocalDateTime> findOldestRequestedAt(RefundStatus status);
}
