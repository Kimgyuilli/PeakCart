package com.peekcart.user.domain.model;

/**
 * 리프레시 토큰 상태 (ADR-0013 D4 reuse detection).
 * <ul>
 *   <li>{@code ACTIVE} — 현재 유효. family 내 정확히 1개만 존재.</li>
 *   <li>{@code ROTATED} — 로테이션으로 대체됨. grace_until 내 재제시는 1회 허용(정상 동시요청), 이후 재제시는 reuse.</li>
 *   <li>{@code REVOKED} — reuse 감지 또는 로그아웃/재로그인으로 무효화됨. 재제시는 항상 거부.</li>
 * </ul>
 */
public enum RefreshTokenStatus {
    ACTIVE,
    ROTATED,
    REVOKED
}
