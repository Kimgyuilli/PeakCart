package com.peekcart.global.auth;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 공유 Redis 를 경유하는 블랙리스트 + family deny read-only 어댑터 (ADR-0014 D1-c · ADR-0013 D4 · PR2b/U5).
 * Redis 조회 실패 시 fail-closed(차단)로 처리하여 차단 토큰 누출을 막는다.
 *
 * <p><b>전환기 dual-read (U5)</b>: write owner(User)는 신키 {@code auth:blacklist:<sha256hex(token)>}
 * 만 기록하지만, 마이그레이션 이전에 등록된 legacy 키 {@code bl:<token>}(원문)도 access token 최대 TTL
 * 동안 남아 있을 수 있다. 신키 우선 + legacy 키 함께 조회한다.
 * <p><b>family deny (ADR-0013 D4)</b>: reuse 감지 family 는 {@code auth:deny:family:<familyId>} 로 등록되며,
 * family_id claim 이 있는 액세스 토큰은 이 키를 함께 조회해 즉시 차단한다(전환기 common-auth enforcement,
 * PR3 에서 Gateway 로 이관). family_id 부재 레거시 토큰은 family deny 를 조회하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RedisTokenBlacklistLookupAdapter implements TokenBlacklistLookupPort {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBlacklistLookupAdapter.class);
    private static final String BLACKLIST_PREFIX = "auth:blacklist:";       // 신키 (해시)
    private static final String LEGACY_PREFIX = "bl:";                      // 전환기 read-only (원문)
    private static final String FAMILY_DENY_PREFIX = "auth:deny:family:";   // reuse 감지 family

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public boolean isBlacklisted(String token) {
        return isBlacklistedOrFamilyDenied(token, null);
    }

    @Override
    public boolean isBlacklistedOrFamilyDenied(String token, String familyId) {
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + TokenHasher.sha256Hex(token)))) {
                return true;
            }
            // 전환기 legacy fallback (U5) — 마이그레이션 이전 등록 토큰
            if (Boolean.TRUE.equals(redisTemplate.hasKey(LEGACY_PREFIX + token))) {
                return true;
            }
            // family deny — family_id claim 이 있는 토큰만 조회 (부재 레거시 토큰은 blacklist 만 검사)
            return StringUtils.hasText(familyId)
                    && Boolean.TRUE.equals(redisTemplate.hasKey(FAMILY_DENY_PREFIX + familyId));
        } catch (RuntimeException e) {
            // fail-closed: 조회 실패 시 차단으로 간주 (ADR-0014 D1-c, ADR-0013 §48 정합)
            log.warn("토큰 블랙리스트/deny 조회 실패 — fail-closed 로 차단", e);
            return true;
        }
    }
}
