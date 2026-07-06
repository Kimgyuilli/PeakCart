package com.peekcart.global.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

/**
 * blacklist read-only 어댑터의 dual-read/fail-closed/miss 시맨틱 단위 회귀 (ADR-0014 D1-c · PR2b/U5 게이트 g).
 * 신키 {@code auth:blacklist:<sha256hex>} 우선 + legacy {@code bl:<token>} fallback.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisTokenBlacklistLookupAdapter 단위 테스트")
class RedisTokenBlacklistLookupAdapterTest {

    @Mock RedisTemplate<String, String> redisTemplate;
    @InjectMocks RedisTokenBlacklistLookupAdapter adapter;

    private static final String TOKEN = "access.token.value";
    private static final String NEW_KEY = "auth:blacklist:" + TokenHasher.sha256Hex(TOKEN);
    private static final String LEGACY_KEY = "bl:" + TOKEN;
    private static final String FAMILY_ID = "family-uuid-0001";
    private static final String FAMILY_DENY_KEY = "auth:deny:family:" + FAMILY_ID;

    @Test
    @DisplayName("신키 hit: auth:blacklist:<hash> 존재 시 blacklisted=true")
    void newKeyHit_returnsTrue() {
        given(redisTemplate.hasKey(NEW_KEY)).willReturn(true);

        assertThat(adapter.isBlacklisted(TOKEN)).isTrue();
    }

    @Test
    @DisplayName("legacy hit (U5 dual-read): 신키 miss + bl:<token> 존재 시 blacklisted=true")
    void legacyKeyHit_returnsTrue() {
        given(redisTemplate.hasKey(NEW_KEY)).willReturn(false);
        given(redisTemplate.hasKey(LEGACY_KEY)).willReturn(true);

        assertThat(adapter.isBlacklisted(TOKEN)).isTrue();
    }

    @Test
    @DisplayName("miss: 신키·legacy 모두 없으면 blacklisted=false (통과)")
    void miss_returnsFalse() {
        given(redisTemplate.hasKey(NEW_KEY)).willReturn(false);
        given(redisTemplate.hasKey(LEGACY_KEY)).willReturn(false);

        assertThat(adapter.isBlacklisted(TOKEN)).isFalse();
    }

    @Test
    @DisplayName("Redis 조회 실패: fail-closed 로 blacklisted=true (차단)")
    void redisFailure_failClosed_returnsTrue() {
        lenient().when(redisTemplate.hasKey(NEW_KEY)).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(adapter.isBlacklisted(TOKEN)).isTrue();
    }

    @Test
    @DisplayName("family deny hit: blacklist miss 여도 auth:deny:family:<id> 존재 시 true (reuse 차단)")
    void familyDenyHit_returnsTrue() {
        given(redisTemplate.hasKey(NEW_KEY)).willReturn(false);
        given(redisTemplate.hasKey(LEGACY_KEY)).willReturn(false);
        given(redisTemplate.hasKey(FAMILY_DENY_KEY)).willReturn(true);

        assertThat(adapter.isBlacklistedOrFamilyDenied(TOKEN, FAMILY_ID)).isTrue();
    }

    @Test
    @DisplayName("family deny miss: blacklist·family 모두 없으면 false (통과)")
    void familyDenyMiss_returnsFalse() {
        given(redisTemplate.hasKey(NEW_KEY)).willReturn(false);
        given(redisTemplate.hasKey(LEGACY_KEY)).willReturn(false);
        given(redisTemplate.hasKey(FAMILY_DENY_KEY)).willReturn(false);

        assertThat(adapter.isBlacklistedOrFamilyDenied(TOKEN, FAMILY_ID)).isFalse();
    }

    @Test
    @DisplayName("family_id 부재(null): family deny 미조회 — blacklist 만 검사(auth:deny:family:null 오조회 금지)")
    void nullFamilyId_skipsFamilyDenyLookup() {
        given(redisTemplate.hasKey(NEW_KEY)).willReturn(false);
        given(redisTemplate.hasKey(LEGACY_KEY)).willReturn(false);

        assertThat(adapter.isBlacklistedOrFamilyDenied(TOKEN, null)).isFalse();
        // family deny 키 조회가 아예 일어나지 않아야 한다
        then(redisTemplate).should(never()).hasKey("auth:deny:family:null");
    }

    @Test
    @DisplayName("family deny 조회 중 Redis 실패: fail-closed 로 true (차단)")
    void familyDenyRedisFailure_failClosed_returnsTrue() {
        given(redisTemplate.hasKey(NEW_KEY)).willReturn(false);
        given(redisTemplate.hasKey(LEGACY_KEY)).willReturn(false);
        given(redisTemplate.hasKey(FAMILY_DENY_KEY)).willThrow(new RedisConnectionFailureException("down"));

        assertThat(adapter.isBlacklistedOrFamilyDenied(TOKEN, FAMILY_ID)).isTrue();
    }
}
