package com.peekcart.user.domain.repository;

import com.peekcart.user.domain.model.RefreshToken;

import java.util.Optional;

public interface RefreshTokenRepository {
    Optional<RefreshToken> findByToken(String token);
    void deleteByToken(String token);
    void deleteByUserId(Long userId);
    void save(RefreshToken refreshToken);
}
