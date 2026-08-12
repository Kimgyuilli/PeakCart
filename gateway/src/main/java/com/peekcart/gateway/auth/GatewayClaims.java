package com.peekcart.gateway.auth;

import java.time.Instant;

/**
 * Gateway 가 검증한 액세스 토큰 클레임 (구현 ③ PR3a).
 *
 * <p>PR3d 에서 servlet 측 {@code TokenClaims} 가 삭제돼(ADR-0014 D2-c exit) 사용자 토큰 클레임 타입은
 * 이제 이것 하나뿐이다. 다운스트림으로는 이 값 자체가 아니라 이를 서명한 내부 토큰이 전달된다(ADR-0017).
 *
 * @param userId     토큰 subject
 * @param role       사용자 역할(USER/ADMIN)
 * @param familyId   refresh family 식별자. PR2 이전 발급 레거시 토큰은 {@code null} —
 *                   {@code app.gateway.internal-token.require-family-id=true}(기본) 이면 발행이 거부된다
 * @param expiration 만료 시각
 */
public record GatewayClaims(Long userId, String role, String familyId, Instant expiration) {
}
