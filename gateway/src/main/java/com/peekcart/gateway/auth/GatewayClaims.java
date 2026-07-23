package com.peekcart.gateway.auth;

import java.time.Instant;

/**
 * Gateway 가 검증한 액세스 토큰 클레임 (구현 ③ PR3a).
 *
 * <p>servlet 측 {@code TokenClaims} 와 같은 형태지만 gateway-local 타입이다 — common-auth 는 servlet
 * 스택이라 의존할 수 없다(B6). PR3d 에서 servlet 측이 삭제되면 이쪽만 남는다.
 *
 * @param userId     토큰 subject
 * @param role       사용자 역할(USER/ADMIN)
 * @param familyId   refresh family 식별자. PR2 이전 발급 레거시 토큰은 {@code null}
 *                   — 전환기(PR3a~c) 한정 수용이며 PR3d 게이트 통과 후 필수화된다(계획 P12)
 * @param expiration 만료 시각
 */
public record GatewayClaims(Long userId, String role, String familyId, Instant expiration) {
}
