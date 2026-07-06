package com.peekcart.support.fixture;

import com.peekcart.global.auth.TokenHasher;
import com.peekcart.user.domain.model.RefreshToken;
import com.peekcart.user.domain.model.RefreshTokenStatus;
import com.peekcart.user.domain.model.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * User 도메인 테스트 픽스처 팩토리.
 * 도메인 단위 테스트 및 Application 레이어 Mockito 테스트에서 공통으로 사용한다.
 */
public class UserFixture {

    public static final Long DEFAULT_ID = 1L;
    public static final String DEFAULT_EMAIL = "user@example.com";
    public static final String DEFAULT_NAME = "테스트유저";
    /** BCrypt hash of "password123" */
    public static final String DEFAULT_PASSWORD_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private UserFixture() {}

    /**
     * ID가 없는 User를 생성한다. 도메인 단위 테스트용.
     */
    public static User user() {
        return User.create(DEFAULT_EMAIL, DEFAULT_PASSWORD_HASH, DEFAULT_NAME);
    }

    /**
     * ID가 설정된 User를 생성한다. Application 레이어 Mockito 테스트용.
     */
    public static User userWithId() {
        return userWithId(DEFAULT_ID);
    }

    /**
     * 지정한 ID가 설정된 User를 생성한다.
     */
    public static User userWithId(Long id) {
        User user = User.create(DEFAULT_EMAIL, DEFAULT_PASSWORD_HASH, DEFAULT_NAME);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /**
     * 지정한 이메일로 User를 생성한다.
     */
    public static User userWithEmail(String email) {
        User user = User.create(email, DEFAULT_PASSWORD_HASH, DEFAULT_NAME);
        ReflectionTestUtils.setField(user, "id", DEFAULT_ID);
        return user;
    }

    public static final String DEFAULT_FAMILY_ID = "family-uuid-0001";

    /**
     * 만료되지 않은 ACTIVE RefreshToken을 생성한다. (원문 → token_hash 로 저장)
     */
    public static RefreshToken activeRefreshToken(Long userId, String rawToken) {
        return RefreshToken.issue(userId, DEFAULT_FAMILY_ID, TokenHasher.sha256Hex(rawToken),
                LocalDateTime.now().plusDays(7));
    }

    /**
     * 이미 만료된 ACTIVE RefreshToken을 생성한다.
     */
    public static RefreshToken expiredRefreshToken(Long userId, String rawToken) {
        return RefreshToken.issue(userId, DEFAULT_FAMILY_ID, TokenHasher.sha256Hex(rawToken),
                LocalDateTime.now().minusDays(1));
    }

    /**
     * ROTATED 상태의 RefreshToken을 생성한다. (grace 재제시 시나리오용)
     */
    public static RefreshToken rotatedRefreshToken(Long userId, String rawToken, Long replacedByTokenId,
                                                   LocalDateTime graceUntil) {
        RefreshToken token = activeRefreshToken(userId, rawToken);
        ReflectionTestUtils.setField(token, "status", RefreshTokenStatus.ROTATED);
        ReflectionTestUtils.setField(token, "replacedByTokenId", replacedByTokenId);
        ReflectionTestUtils.setField(token, "graceUntil", graceUntil);
        return token;
    }

    /**
     * REVOKED 상태의 RefreshToken을 생성한다. (family 무효화 재제시 시나리오용)
     */
    public static RefreshToken revokedRefreshToken(Long userId, String rawToken) {
        RefreshToken token = activeRefreshToken(userId, rawToken);
        ReflectionTestUtils.setField(token, "status", RefreshTokenStatus.REVOKED);
        return token;
    }

    /** 지정한 id 를 설정한 RefreshToken 을 반환한다. */
    public static RefreshToken withId(RefreshToken token, Long id) {
        ReflectionTestUtils.setField(token, "id", id);
        return token;
    }
}
