package com.peekcart.user.infrastructure.redis;

import com.peekcart.global.auth.TokenBlacklistPort;
import com.peekcart.global.auth.TokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Redis 기반 토큰 블랙리스트 + family deny write 저장소 (write owner — ADR-0014 D1-c · ADR-0013 D4).
 * 로그아웃된 액세스 토큰을 블랙리스트에 등록하고, reuse 감지 시 family 를 deny 목록에 등록한다.
 *
 * <p><b>U5 namespace</b>: 블랙리스트 신키 = {@code auth:blacklist:<sha256hex(token)>}
 * (원문 대신 해시 저장 — ADR-0014 "토큰 원문 금지" 충족). read(common-auth)는 전환기 dual-read 로
 * legacy {@code bl:<token>} 도 TTL 동안 함께 조회한다.
 * <p><b>family deny</b>: 키 = {@code auth:deny:family:<familyId>}. value 는 감지 시각(디버깅용,
 * 판정은 키 존재로). read(common-auth/Gateway)가 같은 계약으로 조회한다.
 */
@Repository
@RequiredArgsConstructor
public class TokenBlacklistRepository implements TokenBlacklistPort {

    private static final String BLACKLIST_PREFIX = "auth:blacklist:";
    private static final String FAMILY_DENY_PREFIX = "auth:deny:family:";

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 액세스 토큰을 블랙리스트에 등록한다. 신키(해시)로만 기록한다. TTL은 토큰 잔여 유효 기간으로 설정한다.
     *
     * @param token      블랙리스트에 추가할 토큰
     * @param ttlSeconds Redis 키 만료 시간(초)
     */
    public void addToBlacklist(String token, long ttlSeconds) {
        redisTemplate.opsForValue().set(BLACKLIST_PREFIX + TokenHasher.sha256Hex(token), "1", ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * reuse 감지된 family 를 deny 목록에 등록한다. value 는 감지 시각(epoch ms).
     *
     * @param familyId   무효화할 family 식별자
     * @param ttlSeconds Redis 키 만료 시간(초)
     */
    public void denyFamily(String familyId, long ttlSeconds) {
        redisTemplate.opsForValue().set(FAMILY_DENY_PREFIX + familyId,
                String.valueOf(Instant.now().toEpochMilli()), ttlSeconds, TimeUnit.SECONDS);
    }
}
