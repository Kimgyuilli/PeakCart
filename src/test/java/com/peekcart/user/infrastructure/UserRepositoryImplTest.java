package com.peekcart.user.infrastructure;

import com.peekcart.support.AbstractRepositoryTest;
import com.peekcart.support.fixture.UserFixture;
import com.peekcart.user.domain.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserRepositoryImpl 통합 테스트")
class UserRepositoryImplTest extends AbstractRepositoryTest {

    @Autowired UserJpaRepository userJpaRepository;

    @Test
    @DisplayName("save: User를 저장하면 ID가 부여된다")
    void save_assignsId() {
        User user = User.create(UserFixture.DEFAULT_EMAIL, UserFixture.DEFAULT_PASSWORD_HASH, UserFixture.DEFAULT_NAME);

        User saved = userJpaRepository.save(user);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getEmail()).isEqualTo(UserFixture.DEFAULT_EMAIL);
    }

    @Test
    @DisplayName("findByEmail: 존재하는 이메일이면 User를 반환한다")
    void findByEmail_found() {
        userJpaRepository.save(User.create(UserFixture.DEFAULT_EMAIL, UserFixture.DEFAULT_PASSWORD_HASH, UserFixture.DEFAULT_NAME));

        Optional<User> result = userJpaRepository.findByEmail(UserFixture.DEFAULT_EMAIL);

        assertThat(result).isPresent();
        assertThat(result.get().getEmail()).isEqualTo(UserFixture.DEFAULT_EMAIL);
    }

    @Test
    @DisplayName("findByEmail: 없는 이메일이면 empty를 반환한다")
    void findByEmail_notFound() {
        Optional<User> result = userJpaRepository.findByEmail("none@example.com");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("existsByEmail: 존재하는 이메일이면 true를 반환한다")
    void existsByEmail_true() {
        userJpaRepository.save(User.create(UserFixture.DEFAULT_EMAIL, UserFixture.DEFAULT_PASSWORD_HASH, UserFixture.DEFAULT_NAME));

        assertThat(userJpaRepository.existsByEmail(UserFixture.DEFAULT_EMAIL)).isTrue();
    }

    @Test
    @DisplayName("existsByEmail: 없는 이메일이면 false를 반환한다")
    void existsByEmail_false() {
        assertThat(userJpaRepository.existsByEmail("none@example.com")).isFalse();
    }

    @Test
    @DisplayName("findById: 존재하는 ID이면 User를 반환한다")
    void findById_found() {
        User saved = userJpaRepository.save(User.create(UserFixture.DEFAULT_EMAIL, UserFixture.DEFAULT_PASSWORD_HASH, UserFixture.DEFAULT_NAME));

        Optional<User> result = userJpaRepository.findById(saved.getId());

        assertThat(result).isPresent();
    }
}
