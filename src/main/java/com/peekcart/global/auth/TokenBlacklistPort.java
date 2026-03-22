package com.peekcart.global.auth;

import java.util.Optional;

/**
 * 토큰 블랙리스트 및 그레이스 피리어드 저장소 추상화.
 * global 패키지에 위치하여 JwtFilter(global.jwt)와 AuthService(user.application) 모두 참조 가능.
 */
public interface TokenBlacklistPort {

    /** 액세스 토큰을 블랙리스트에 등록한다. TTL은 토큰 잔여 유효 기간으로 설정한다. */
    void addToBlacklist(String token, long ttlSeconds);

    /** 토큰이 블랙리스트에 등록되어 있는지 확인한다. */
    boolean isBlacklisted(String token);

    /** 토큰 로테이션 직후 구 토큰 재사용을 허용하기 위해 그레이스 피리어드 항목을 저장한다. */
    void addGracePeriod(String token, long userId, long ttlSeconds);

    /**
     * 그레이스 피리어드가 유효한 토큰에서 소유자 ID를 원자적으로 조회하고 즉시 삭제한다.
     * 조회와 삭제가 원자적으로 처리되어 동시 요청에 의한 이중 발급을 방지한다.
     *
     * @return 소유자 userId, 그레이스 피리어드가 없거나 만료됐으면 {@code empty}
     */
    Optional<Long> consumeGracePeriod(String token);
}
