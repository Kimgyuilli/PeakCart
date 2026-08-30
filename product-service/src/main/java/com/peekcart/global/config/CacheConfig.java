package com.peekcart.global.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis 기반 캐시 설정.
 * <p>JSON 직렬화, 캐시별 TTL, 키 프리픽스({@code cache:})를 구성한다.
 * 기존 JWT 블랙리스트 키({@code bl:}, {@code gp:})와 네임스페이스를 분리한다.
 *
 * <p>Phase 3 Task 3-4 부하 테스트 시 캐싱 전/후 TPS 비교를 위해
 * {@code peekcart.cache.enabled} 프로퍼티로 캐시 매니저를 토글한다.
 * 기본값은 {@code true} 이며, {@code false} 일 경우 {@link NoOpCacheManager} 가 주입되어
 * {@code @Cacheable} 이 pass-through 로 동작한다.
 *
 * <p><b>{@link CachingConfigurer} 를 구현하는 이유</b> (L-006, 구현 ⑤): Spring 은
 * {@code CacheErrorHandler} 를 <b>{@code CachingConfigurer} 빈에서만</b> 수집한다
 * ({@code AbstractCachingConfiguration#setConfigurers}). 맨 {@code @Bean CacheErrorHandler} 는
 * 조용히 무시되므로 반드시 여기서 {@link #errorHandler()} 로 공급해야 한다.
 *
 * <p>{@code cacheManager()}/{@code cacheResolver()}/{@code keyGenerator()} 는
 * <b>오버라이드하지 않는다</b>. 인터페이스 기본 구현이 null 을 반환하면
 * {@code CacheAspectSupport#afterSingletonsInstantiated} 가 {@code CacheManager} 타입 조회로
 * 폴백하므로, 아래 {@code @ConditionalOnProperty} 2빈 구조를 그대로 쓸 수 있다.
 * 명시 배선을 넣으면 두 빈 사이에서 모호성만 만든다.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    public static final String PRODUCT_DETAIL_CACHE = "product";
    public static final String PRODUCT_LIST_CACHE = "products";

    /**
     * {@link MeterRegistry} 를 <b>직접 주입하지 않는다</b>. {@code @Configuration} 클래스가 레지스트리를
     * 생성자에서 요구하면 {@code MeterRegistryCustomizer}(공통 태그 {@code application=product-service} 등)
     * 가 적용되기 <b>전에</b> 레지스트리가 만들어져, 이후 모든 메트릭에서 그 태그가 사라진다
     * (ADR-0009 S2 위반 — {@code ProductObservabilityMetricsIntegrationTest} 가 이를 잡는다).
     * {@link ObjectProvider} 로 미뤄 첫 사용 시점에 완성된 레지스트리를 받는다.
     */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public CacheConfig(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Bean
    @ConditionalOnProperty(name = "peekcart.cache.enabled", havingValue = "true", matchIfMissing = true)
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .activateDefaultTyping(
                        BasicPolymorphicTypeValidator.builder()
                                .allowIfBaseType("com.peekcart.")
                                .allowIfBaseType("java.lang.")
                                .allowIfBaseType("java.util.")
                                .build(),
                        ObjectMapper.DefaultTyping.EVERYTHING,
                        JsonTypeInfo.As.PROPERTY)
                .build();

        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(serializer))
                .prefixCacheNameWith("cache:")
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues();

        RedisCacheConfiguration productDetailConfig = defaultConfig
                .entryTtl(Duration.ofMinutes(30));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration(PRODUCT_DETAIL_CACHE, productDetailConfig)
                .withCacheConfiguration(PRODUCT_LIST_CACHE, defaultConfig)
                .enableStatistics()
                .build();
    }

    /**
     * 캐시 비활성화 시 주입되는 NoOp 캐시 매니저.
     * <p>부하 테스트 baseline 측정 (캐시 OFF) 전용.
     */
    @Bean
    @ConditionalOnProperty(name = "peekcart.cache.enabled", havingValue = "false")
    public CacheManager noOpCacheManager() {
        return new NoOpCacheManager();
    }

    /**
     * Redis 장애 시 조회를 DB 로 흘리는 fail-open 핸들러 (L-006).
     * <p>정책 근거와 콜백별 트레이드오프는 {@link ResilientCacheErrorHandler} javadoc 참고.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new ResilientCacheErrorHandler(meterRegistryProvider);
    }
}
