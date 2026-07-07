package com.peekcart.user.domain.repository;

import com.peekcart.user.domain.model.RefreshToken;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 리프레시 토큰 도메인 리포지터리 인터페이스 (ADR-0013 D4 reuse detection).
 * <p>동시성이 걸린 상태전이는 조건부 UPDATE(affected rows)로 원자성을 보장한다 —
 * 삭제 기반 모델의 {@code deleteByToken} affected 판정을 상태전이로 이전한 것.
 */
public interface RefreshTokenRepository {

    /** 토큰 해시로 리프레시 토큰을 조회한다. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** 리프레시 토큰을 저장하고 (id 포함) 영속 인스턴스를 반환한다. */
    RefreshToken save(RefreshToken refreshToken);

    /**
     * ACTIVE 토큰을 원자적으로 ROTATED 로 전이한다(정상 로테이션). status='ACTIVE' 인 한 행만 전이되며,
     * 동시 요청 중 정확히 1건만 성공(affected=1)한다.
     *
     * @return 전이된 행 수 (0 = 이미 로테이션됨 = 동시 요청 패배)
     */
    int rotateActive(String tokenHash, Long newTokenId, LocalDateTime rotatedAt, LocalDateTime graceUntil);

    /**
     * grace_until 이 유효한 ROTATED 토큰의 grace 를 원자적으로 1회 소비한다(grace_until→now).
     * 동시 재제시 중 정확히 1건만 성공(affected=1)하여 이중 발급을 차단한다.
     *
     * @return 소비된 행 수 (0 = grace 만료/이미 소비 = reuse 후보)
     */
    int consumeGraceOnce(String tokenHash, LocalDateTime now);

    /**
     * grace 성공 시 기존 replacement 를 강제로 ROTATED 전이한다(grace 미부여 — 재소비 순환 금지).
     * status='ACTIVE' 인 경우에만 전이하여 이미 전이된 토큰을 덮어쓰지 않는다.
     */
    int forceRotate(Long tokenId, Long newTokenId, LocalDateTime rotatedAt);

    /** family 내 미무효 토큰을 전부 REVOKED 로 전이한다(reuse 감지). */
    void revokeFamily(String familyId);

    /** 회원의 미무효 토큰을 전부 REVOKED 로 전이한다(로그아웃 / 재로그인). */
    void revokeAllByUserId(Long userId);
}
