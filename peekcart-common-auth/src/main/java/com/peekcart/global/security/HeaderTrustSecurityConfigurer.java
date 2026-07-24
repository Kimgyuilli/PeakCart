package com.peekcart.global.security;

import com.peekcart.global.filter.MdcFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;

/**
 * header-trust 재사용 보안 설정 기여(ADR-0013 D3 · PR3c). {@link JwtSecurityConfigurer} 를 대체하되
 * <b>필터만</b> 교체한다 — {@link com.peekcart.global.jwt.JwtFilter}(서명 검증) → {@link HeaderAuthenticationFilter}
 * (Gateway 신뢰 헤더). csrf off·STATELESS·예외 핸들러·MdcFilter 순서 등 공통 정책은 그대로 보존한다
 * (review #6: configurer 스왑이 "필터만" 이 아니라 나머지 정책까지 바꾸면 401/403·MDC 계약이 조용히 달라진다).
 *
 * <p>각 서비스는 자기 모듈에서 {@code SecurityFilterChain} 빈을 정확히 1개 생성하고 자기 PUBLIC_URLS 만 넘긴다.
 * 서명 검증은 하지 않으므로 {@code JwtTokenVerifier}/{@code TokenBlacklistLookupPort} 를 의존하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class HeaderTrustSecurityConfigurer {

    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;

    /**
     * 공통 header-trust 보안 정책을 {@code http} 에 적용한다. 호출자가 {@code http.build()} 로 단일 체인을 완성한다.
     *
     * @param http       서비스의 SecurityFilterChain 빌더
     * @param publicUrls 해당 서비스의 인증 면제 URL
     */
    public void apply(HttpSecurity http, String[] publicUrls) throws Exception {
        HeaderAuthenticationFilter headerFilter = new HeaderAuthenticationFilter(authenticationEntryPoint);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(publicUrls).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .addFilterBefore(headerFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new MdcFilter(), HeaderAuthenticationFilter.class);
    }
}
