package com.peekcart.user.domain.model;

import com.peekcart.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 리프레시 토큰 도메인 엔티티 (ADR-0013 D4 reuse detection).
 * <p>삭제 기반 로테이션 → {@code family_id}/{@code status} 상태전이 모델. 평문 토큰은 저장하지 않고
 * SHA-256 해시({@code token_hash})만 보관한다. 동시성이 걸린 상태전이(ACTIVE→ROTATED, grace 소비,
 * force-rotate)는 원자성 보장을 위해 리포지터리 조건부 UPDATE 로 수행한다(엔티티 setter 미노출).
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 로테이션 체인을 묶는 family 식별자 (UUID). reuse 감지 시 family 전체 무효화 단위. */
    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    /** 리프레시 토큰 원문(UUID)의 SHA-256 hex 다이제스트 (평문 미저장). */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefreshTokenStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "rotated_at")
    private LocalDateTime rotatedAt;

    /** ROTATED 토큰의 grace 종료 시각. grace_until 내 재제시는 1회 허용(정상 동시요청). */
    @Column(name = "grace_until")
    private LocalDateTime graceUntil;

    /** 이 토큰을 대체한 새 토큰의 id (단방향 — 자기참조/순환 금지). */
    @Column(name = "replaced_by_token_id")
    private Long replacedByTokenId;

    private RefreshToken(Long userId, String familyId, String tokenHash, LocalDateTime expiresAt) {
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.status = RefreshTokenStatus.ACTIVE;
        this.expiresAt = expiresAt;
    }

    /**
     * 새 ACTIVE 리프레시 토큰을 생성한다.
     *
     * @param userId    토큰 소유자 ID
     * @param familyId  로테이션 family (login/signup=신규, refresh=기존 승계)
     * @param tokenHash 토큰 원문의 SHA-256 hex
     * @param expiresAt 만료 일시
     */
    public static RefreshToken issue(Long userId, String familyId, String tokenHash, LocalDateTime expiresAt) {
        return new RefreshToken(userId, familyId, tokenHash, expiresAt);
    }

    /** 현재 시각 기준으로 만료 여부를 반환한다. */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }
}
