package com.peekcart.global.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Redis 장애 시 캐시 경로를 fail-open 으로 흘리는 {@link CacheErrorHandler} (L-006, 구현 ⑤).
 *
 * <p><b>왜 fail-open 인가</b>: Redis 는 5서비스 공유 인프라다. 기본
 * {@code SimpleCacheErrorHandler} 는 캐시 예외를 그대로 되던지므로, Redis 가 죽으면
 * DB 는 멀쩡한데도 상품 조회 API 전체가 5xx 로 떨어진다. 캐시는 가용성 단일점이 아니어야 한다.
 *
 * <p><b>왜 gateway 는 정반대인가</b>: {@code FailClosedRedisRateLimiter} 는 Redis 장애 시
 * <b>fail-closed</b> 로 거부한다(ADR-0013 D3). rate limit 은 <i>보호 장치</i>라 유실되면
 * 남용이 무제한 통과하지만, 조회 캐시는 <i>가속 장치</i>라 유실돼도 DB 라는 정답이 남는다.
 * 같은 Redis 를 두 정책으로 다루는 이유가 이 차이다.
 *
 * <p><b>콜백별 결과가 다르다</b> — 그래서 정책도 다르다.
 * <ul>
 *   <li><b>get 실패</b>: {@code CacheInterceptor} 가 캐시 미스로 간주해 타깃 메서드를 실행한다
 *       → DB 조회 → 정상 응답. 이것이 L-006 이 원하는 동작이다.
 *   <li><b>put 실패</b>: 응답은 이미 산출됐으므로 정확성은 유지된다. 다만 캐시가 채워지지 않아
 *       <b>모든 후속 요청이 DB 를 다시 친다</b> — Redis 장애가 DB 부하로 전이되고, 동시 요청
 *       하에서는 cache stampede 로 커넥션 풀을 압박한다. 무해하지 않다. 완화 기구는 후속(M7).
 *   <li><b>evict/clear 실패</b>: DB 는 커밋되고 무효화만 실패한다 → Redis 복구 후에도 TTL
 *       (상세 30m / 목록 10m) 만료 전까지 <b>stale 가격·상태가 서빙</b>된다. 판매중단 상품이
 *       최대 30분 노출될 수 있다. 조용히 넘기면 아무도 모르므로 <b>WARN + 메트릭</b>으로 남긴다.
 * </ul>
 *
 * <p>복구 방침(TTL 만료 대기)과 수동 키 삭제 절차는 {@code docs/runbooks/redis-cache-fallback.md}.
 */
@Slf4j
public class ResilientCacheErrorHandler implements CacheErrorHandler {

    /** Micrometer {@code cache.fallback} → Prometheus {@code cache_fallback_total}. */
    static final String METRIC_NAME = "cache.fallback";

    /** 지연 해석 — 이유는 {@code CacheConfig#meterRegistryProvider} javadoc 참고. */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public ResilientCacheErrorHandler(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        // 조용히 삼킨다 — 미스로 취급돼 타깃 메서드(DB)가 실행된다. 흔적은 메트릭에 남는다.
        count(cache, "get");
        log.debug("캐시 조회 실패 — DB 로 우회. cache={}, key={}", cacheName(cache), key, exception);
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        count(cache, "put");
        log.debug("캐시 적재 실패 — 후속 요청이 DB 를 친다. cache={}, key={}", cacheName(cache), key, exception);
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        count(cache, "evict");
        // stale 창이 열렸다. TTL 만료 전까지 낡은 값이 서빙된다 — WARN 으로 올린다.
        log.warn("캐시 무효화 실패 — TTL 만료까지 stale 값이 서빙될 수 있다. cache={}, key={}",
                cacheName(cache), key, exception);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        count(cache, "clear");
        log.warn("캐시 전체 무효화 실패 — TTL 만료까지 stale 값이 서빙될 수 있다. cache={}",
                cacheName(cache), exception);
    }

    private void count(Cache cache, String operation) {
        Counter.builder(METRIC_NAME)
                .tag("cache", cacheName(cache))
                .tag("operation", operation)
                .description("Redis 캐시 연산 실패로 fallback 이 발동한 횟수 (L-006)")
                .register(meterRegistryProvider.getObject())
                .increment();
    }

    private static String cacheName(Cache cache) {
        return cache == null ? "unknown" : cache.getName();
    }
}
