package com.peekcart.user.infrastructure;

import com.peekcart.user.domain.model.RefreshToken;
import com.peekcart.user.domain.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * {@link com.peekcart.user.domain.repository.RefreshTokenRepository}의 JPA 구현체.
 */
@Repository
@RequiredArgsConstructor
public class RefreshTokenRepositoryImpl implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository refreshTokenJpaRepository;

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return refreshTokenJpaRepository.findByTokenHash(tokenHash);
    }

    @Override
    public RefreshToken save(RefreshToken refreshToken) {
        return refreshTokenJpaRepository.save(refreshToken);
    }

    @Override
    public int rotateActive(String tokenHash, Long newTokenId, LocalDateTime rotatedAt, LocalDateTime graceUntil) {
        return refreshTokenJpaRepository.rotateActive(tokenHash, newTokenId, rotatedAt, graceUntil);
    }

    @Override
    public int consumeGraceOnce(String tokenHash, LocalDateTime now) {
        return refreshTokenJpaRepository.consumeGraceOnce(tokenHash, now);
    }

    @Override
    public int forceRotate(Long tokenId, Long newTokenId, LocalDateTime rotatedAt) {
        return refreshTokenJpaRepository.forceRotate(tokenId, newTokenId, rotatedAt);
    }

    @Override
    public void revokeFamily(String familyId) {
        refreshTokenJpaRepository.revokeFamily(familyId);
    }

    @Override
    public void revokeAllByUserId(Long userId) {
        refreshTokenJpaRepository.revokeAllByUserId(userId);
    }
}
