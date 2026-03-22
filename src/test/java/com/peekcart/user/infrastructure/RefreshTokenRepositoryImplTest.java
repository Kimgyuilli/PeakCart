package com.peekcart.user.infrastructure;

import com.peekcart.support.AbstractRepositoryTest;
import com.peekcart.support.fixture.UserFixture;
import com.peekcart.user.domain.model.RefreshToken;
import com.peekcart.user.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RefreshTokenRepositoryImpl 통합 테스트")
class RefreshTokenRepositoryImplTest extends AbstractRepositoryTest {

    @Autowired RefreshTokenJpaRepository refreshTokenJpaRepository;
    @Autowired UserJpaRepository userJpaRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        User user = userJpaRepository.save(
                User.create(UserFixture.DEFAULT_EMAIL, UserFixture.DEFAULT_PASSWORD_HASH, UserFixture.DEFAULT_NAME));
        userId = user.getId();
    }

    @Test
    @DisplayName("save: RefreshToken을 저장한다")
    void save_persists() {
        RefreshToken token = RefreshToken.create(userId, "token-value", LocalDateTime.now().plusDays(7));

        refreshTokenJpaRepository.save(token);

        assertThat(refreshTokenJpaRepository.findByToken("token-value")).isPresent();
    }

    @Test
    @DisplayName("findByToken: 존재하는 토큰이면 RefreshToken을 반환한다")
    void findByToken_found() {
        refreshTokenJpaRepository.save(RefreshToken.create(userId, "my-token", LocalDateTime.now().plusDays(7)));

        Optional<RefreshToken> result = refreshTokenJpaRepository.findByToken("my-token");

        assertThat(result).isPresent();
        assertThat(result.get().getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("findByToken: 없는 토큰이면 empty를 반환한다")
    void findByToken_notFound() {
        Optional<RefreshToken> result = refreshTokenJpaRepository.findByToken("non-existent");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("deleteByToken: 해당 토큰만 삭제된다")
    void deleteByToken_deletesSpecificToken() {
        refreshTokenJpaRepository.save(RefreshToken.create(userId, "token-a", LocalDateTime.now().plusDays(7)));
        refreshTokenJpaRepository.save(RefreshToken.create(userId, "token-b", LocalDateTime.now().plusDays(7)));

        refreshTokenJpaRepository.deleteByToken("token-a");

        assertThat(refreshTokenJpaRepository.findByToken("token-a")).isEmpty();
        assertThat(refreshTokenJpaRepository.findByToken("token-b")).isPresent();
    }

    @Test
    @DisplayName("deleteByUserId: 해당 유저의 모든 토큰이 삭제된다")
    void deleteByUserId_deletesAllTokensForUser() {
        refreshTokenJpaRepository.save(RefreshToken.create(userId, "token-1", LocalDateTime.now().plusDays(7)));
        refreshTokenJpaRepository.save(RefreshToken.create(userId, "token-2", LocalDateTime.now().plusDays(7)));

        refreshTokenJpaRepository.deleteByUserId(userId);

        assertThat(refreshTokenJpaRepository.findByToken("token-1")).isEmpty();
        assertThat(refreshTokenJpaRepository.findByToken("token-2")).isEmpty();
    }
}
