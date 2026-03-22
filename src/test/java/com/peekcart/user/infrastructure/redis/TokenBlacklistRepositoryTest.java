package com.peekcart.user.infrastructure.redis;

import com.peekcart.support.AbstractRedisTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenBlacklistRepository 통합 테스트")
class TokenBlacklistRepositoryTest extends AbstractRedisTest {

    @Autowired TokenBlacklistRepository tokenBlacklistRepository;

    // ── blacklist ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("addToBlacklist: 등록된 토큰은 isBlacklisted가 true를 반환한다")
    void addToBlacklist_isBlacklisted_returnsTrue() {
        String token = "blacklisted-token";

        tokenBlacklistRepository.addToBlacklist(token, 60);

        assertThat(tokenBlacklistRepository.isBlacklisted(token)).isTrue();
    }

    @Test
    @DisplayName("isBlacklisted: 등록되지 않은 토큰은 false를 반환한다")
    void isBlacklisted_unregistered_returnsFalse() {
        assertThat(tokenBlacklistRepository.isBlacklisted("not-blacklisted")).isFalse();
    }

    // ── grace period ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("addGracePeriod: 등록된 토큰에서 userId를 조회할 수 있다")
    void addGracePeriod_getGracePeriodUserId_returnsUserId() {
        String token = "old-refresh-token";
        long userId = 42L;

        tokenBlacklistRepository.addGracePeriod(token, userId, 10);

        Optional<Long> result = tokenBlacklistRepository.getGracePeriodUserId(token);
        assertThat(result).contains(userId);
    }

    @Test
    @DisplayName("getGracePeriodUserId: 등록되지 않은 토큰이면 empty를 반환한다")
    void getGracePeriodUserId_notFound_returnsEmpty() {
        Optional<Long> result = tokenBlacklistRepository.getGracePeriodUserId("no-grace-period");

        assertThat(result).isEmpty();
    }
}
