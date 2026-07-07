package com.peekcart.global.auth;

/**
 * 토큰 블랙리스트 + family deny read-only 조회 추상화 (ADR-0014 D1-c · ADR-0013 D4).
 * 검증 모듈(common-auth)·게이트웨이가 소유하는 read 계약. write(등록/deny)는 User 전속
 * {@code TokenBlacklistPort}.
 *
 * <p>조회 시맨틱:
 * <ul>
 *   <li>miss(없음) = {@code false} → 통과</li>
 *   <li>Redis 조회 실패 = {@code true} → fail-closed(요청 거부)</li>
 * </ul>
 */
public interface TokenBlacklistLookupPort {

    /** 토큰이 블랙리스트에 등록되어 있는지 확인한다. 조회 실패 시 fail-closed. */
    boolean isBlacklisted(String token);

    /**
     * 토큰 블랙리스트 <b>또는</b> family deny 등록 여부를 확인한다(ADR-0013 D4 전환기 enforcement).
     * <p>{@code familyId} 가 null/blank 면(PR2 이전 발급 레거시 토큰) family deny 는 조회하지 않고
     * 블랙리스트만 검사한다 — claim 부재는 Redis "조회 실패"가 아니므로 fail-closed 대상이 아니다.
     *
     * @param token    액세스 토큰 원문
     * @param familyId 액세스 토큰의 {@code family_id} claim (없으면 null)
     * @return 차단 대상이면 {@code true}. 조회 실패 시 fail-closed({@code true}).
     */
    boolean isBlacklistedOrFamilyDenied(String token, String familyId);
}
