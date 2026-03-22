package com.peekcart.user.domain.repository;

import com.peekcart.user.domain.model.RefreshToken;

import java.util.Optional;

/**
 * 리프레시 토큰 도메인 리포지터리 인터페이스.
 */
public interface RefreshTokenRepository {
    /** 토큰 값으로 리프레시 토큰을 조회한다. */
    Optional<RefreshToken> findByToken(String token);
    /** 특정 토큰 값의 레코드를 삭제한다. */
    void deleteByToken(String token);
    /** 회원의 모든 리프레시 토큰을 삭제한다. (로그아웃 / 재로그인 시 사용) */
    void deleteByUserId(Long userId);
    /** 리프레시 토큰을 저장한다. */
    void save(RefreshToken refreshToken);
}
