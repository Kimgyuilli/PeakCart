package com.peekcart.user.infrastructure;

import com.peekcart.user.domain.RefreshToken;
import com.peekcart.user.domain.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRepositoryImpl implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository refreshTokenJpaRepository;

    @Override
    public Optional<RefreshToken> findByToken(String token) {
        return refreshTokenJpaRepository.findByToken(token);
    }

    @Override
    public void deleteByToken(String token) {
        refreshTokenJpaRepository.deleteByToken(token);
    }

    @Override
    public void deleteByUserId(Long userId) {
        refreshTokenJpaRepository.deleteByUserId(userId);
    }

    @Override
    public void save(RefreshToken refreshToken) {
        refreshTokenJpaRepository.save(refreshToken);
    }
}
