package com.peekcart.user.domain.repository;

import java.util.Optional;

/**
 * 토큰 블랙리스트와 그레이스 피리어드를 관리하는 포트.
 * Application 레이어가 Redis 구현 세부사항에 직접 의존하지 않도록 역전시킨다.
 */
public interface TokenBlacklistPort {

    /**
     * 액세스 토큰을 블랙리스트에 등록한다.
     *
     * @param token      블랙리스트에 추가할 토큰
     * @param ttlSeconds 만료 시간(초)
     */
    void addToBlacklist(String token, long ttlSeconds);

    /**
     * 토큰이 블랙리스트에 등록되어 있는지 확인한다.
     *
     * @return 블랙리스트에 있으면 {@code true}
     */
    boolean isBlacklisted(String token);

    /**
     * 토큰 로테이션 직후 구 토큰에 대한 그레이스 피리어드를 등록한다.
     *
     * @param token      구 리프레시 토큰
     * @param userId     토큰 소유자 ID
     * @param ttlSeconds 그레이스 피리어드 유효 시간(초)
     */
    void addGracePeriod(String token, long userId, long ttlSeconds);

    /**
     * 그레이스 피리어드가 유효한 토큰에서 소유자 ID를 조회한다.
     *
     * @return 소유자 userId, 만료됐으면 {@code empty}
     */
    Optional<Long> getGracePeriodUserId(String token);
}
