package com.peekcart.user.domain;

import java.util.Optional;

public interface RefreshTokenRepository {
    Optional<RefreshToken> findByToken(String token);
    void deleteByToken(String token);
    void deleteByUserId(Long userId);
    RefreshToken save(RefreshToken refreshToken);
}
