package com.peekcart.support;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * {@code @DataJpaTest} 슬라이스에서 Flyway 마이그레이션을 활성화하는 설정.
 * {@code @DataJpaTest}는 Flyway Auto-configuration을 기본 제외하므로 명시적으로 포함시킨다.
 */
@Configuration
@EnableConfigurationProperties(FlywayProperties.class)
public class FlywayTestConfig {

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
    }
}
