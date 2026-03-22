package com.peekcart.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis 통합 테스트 베이스 클래스.
 *
 * <ul>
 *   <li>Redis GenericContainer를 static으로 공유하여 컨테이너 재사용</li>
 *   <li>{@link DynamicPropertySource}로 Redis host/port 동적 주입</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractRedisTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}
