package com.peekcart.support;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Infrastructure 레이어 JPA Repository 통합 테스트 베이스 클래스.
 *
 * <ul>
 *   <li>MySQL Testcontainer를 클래스 간 공유(static)하여 컨테이너 재사용</li>
 *   <li>Flyway 마이그레이션이 자동 적용되어 실제 스키마로 테스트</li>
 *   <li>{@link ServiceConnection}으로 datasource 자동 연결</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FlywayTestConfig.class)
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("peekcart_test");
}
