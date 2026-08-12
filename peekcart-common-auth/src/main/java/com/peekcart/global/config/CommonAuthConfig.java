package com.peekcart.global.config;

import com.peekcart.global.jwt.JwtAuthProperties;
import com.peekcart.global.jwt.JwtKeyProperties;
import com.peekcart.global.security.InternalTokenProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * common-auth 모듈 설정. {@link JwtAuthProperties}(HS256 대칭키) · {@link JwtKeyProperties}(RS256 비대칭키) ·
 * {@link InternalTokenProperties}(Gateway 내부 토큰 검증) 설정 계약을 활성화한다
 * (ADR-0014 D1-b · ADR-0013 D1 · ADR-0017 D3). 각 서비스의 컴포넌트 스캔(com.peekcart.*)에
 * 포함되어 전 서비스에서 동일 계약이 바인딩된다.
 *
 * <p>{@link JwtKeyProperties}({@code app.jwt.rs256.*}) 는 User access token 검증/JWKS 게시 전용으로 남는다 —
 * Gateway 내부 토큰 키는 {@link InternalTokenProperties}({@code app.internal-token.*}) 가 따로 소유한다.
 * 같은 곳에 두면 JWKS 로 내부 신뢰 앵커가 새기 때문이다(ADR-0017 D3).
 */
@Configuration
@EnableConfigurationProperties({JwtAuthProperties.class, JwtKeyProperties.class, InternalTokenProperties.class})
public class CommonAuthConfig {
}
