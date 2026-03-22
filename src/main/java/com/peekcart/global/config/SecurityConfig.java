package com.peekcart.global.config;

import com.peekcart.global.jwt.JwtFilter;
import com.peekcart.global.jwt.JwtProvider;
import com.peekcart.global.security.JwtAccessDeniedHandler;
import com.peekcart.global.security.JwtAuthenticationEntryPoint;
import com.peekcart.user.domain.repository.TokenBlacklistPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 설정. Stateless JWT 방식으로 동작하며
 * 인증이 필요 없는 공개 URL을 제외한 모든 요청에 인증을 요구한다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final TokenBlacklistPort tokenBlacklistPort;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;

    private static final String[] PUBLIC_URLS = {
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/api-docs/**"
    };

    /**
     * 세션 비활성화, CSRF 비활성화, JWT 필터 등록, URL 인가 규칙을 구성한다.
     * {@link JwtFilter}는 빈으로 등록하지 않고 여기서 직접 생성하여 컴포넌트 스캔 대상에서 제외한다.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        JwtFilter jwtFilter = new JwtFilter(jwtProvider, tokenBlacklistPort);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_URLS).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** BCrypt 기반 패스워드 인코더를 빈으로 등록한다. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
