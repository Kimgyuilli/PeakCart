package com.peekcart.global.config;

import com.peekcart.global.jwt.JwtAuthProperties;
import com.peekcart.global.jwt.JwtKeyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * common-auth 모듈 설정. {@link JwtAuthProperties}(HS256 대칭키) 와 {@link JwtKeyProperties}(RS256 비대칭키)
 * 설정 계약을 활성화한다 (ADR-0014 D1-b · ADR-0013 D1). 각 서비스의 컴포넌트 스캔(com.peekcart.*)에
 * 포함되어 전 서비스에서 동일 계약이 바인딩된다.
 */
@Configuration
@EnableConfigurationProperties({JwtAuthProperties.class, JwtKeyProperties.class})
public class CommonAuthConfig {
}
