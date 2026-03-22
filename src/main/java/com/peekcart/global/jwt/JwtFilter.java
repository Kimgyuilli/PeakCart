package com.peekcart.global.jwt;

import com.peekcart.global.auth.TokenClaims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.peekcart.user.infrastructure.redis.TokenBlacklistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 요청마다 {@code Authorization} 헤더에서 Bearer 토큰을 추출하고
 * 유효성 검증 후 {@code SecurityContext}에 인증 정보를 설정하는 필터.
 * 블랙리스트에 등록된 토큰은 인증을 거부한다.
 */
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final TokenBlacklistRepository tokenBlacklistRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && jwtProvider.isValid(token) && !tokenBlacklistRepository.isBlacklisted(token)) {
            TokenClaims claims = jwtProvider.parseToken(token);

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    claims.userId(), null, List.of(new SimpleGrantedAuthority("ROLE_" + claims.role()))
            );
            authentication.setDetails(token);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    /**
     * {@code Authorization: Bearer <token>} 헤더에서 토큰 값만 추출한다.
     *
     * @return 토큰 문자열, 헤더가 없거나 형식이 맞지 않으면 {@code null}
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
