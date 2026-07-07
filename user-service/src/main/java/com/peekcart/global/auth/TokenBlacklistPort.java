package com.peekcart.global.auth;

/**
 * 토큰 블랙리스트 + family deny write 저장소 추상화 (ADR-0014 D1-c · ADR-0013 D4, User 전속 write owner).
 * read 조회는 common-auth {@code TokenBlacklistLookupPort}로 분리됐다.
 */
public interface TokenBlacklistPort {

    /** 액세스 토큰을 블랙리스트에 등록한다. TTL은 토큰 잔여 유효 기간으로 설정한다. */
    void addToBlacklist(String token, long ttlSeconds);

    /**
     * reuse 감지 시 family 전체를 deny 목록에 등록한다(ADR-0013 D4). Gateway/common-auth read 경로가
     * 이 키를 조회해 해당 family 의 이미 발급된 액세스 토큰을 즉시 차단한다. TTL 은 access token 최대
     * 잔여 유효기간 이상으로 두어 family 소속 토큰이 자연 소멸할 때까지 차단을 유지한다.
     *
     * @param familyId   무효화할 family 식별자 (토큰 원문 아님 — claim 노출 식별자)
     * @param ttlSeconds deny 키 만료 시간(초)
     */
    void denyFamily(String familyId, long ttlSeconds);
}
