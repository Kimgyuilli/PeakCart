package com.peekcart.user.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.jwt.JwtProvider;
import com.peekcart.user.domain.model.RefreshToken;
import com.peekcart.user.domain.repository.RefreshTokenRepository;
import com.peekcart.user.domain.model.User;
import com.peekcart.user.domain.exception.UserException;
import com.peekcart.user.domain.repository.UserRepository;
import com.peekcart.user.infrastructure.redis.TokenBlacklistRepository;
import com.peekcart.user.presentation.dto.request.LoginRequest;
import com.peekcart.user.presentation.dto.request.SignupRequest;
import com.peekcart.user.presentation.dto.response.TokenResponse;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 회원가입 / 로그인 / 로그아웃 / 토큰 재발급을 처리하는 애플리케이션 서비스.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklistRepository tokenBlacklistRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.jwt.access-token-expiry}")
    private long accessTokenExpiry;

    @Value("${app.jwt.refresh-token-expiry}")
    private long refreshTokenExpiry;

    /**
     * 신규 회원을 등록하고 토큰을 발급한다.
     *
     * @param request 회원가입 요청 (이메일, 비밀번호, 이름)
     * @return 발급된 액세스 토큰 및 리프레시 토큰
     * @throws com.peekcart.user.domain.exception.UserException 이메일 중복 시 {@code USR-001}
     */
    public TokenResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new UserException(ErrorCode.USR_001);
        }
        String passwordHash = passwordEncoder.encode(request.password());
        User user = userRepository.save(User.create(request.email(), passwordHash, request.name()));
        return issueTokens(user);
    }

    /**
     * 이메일과 비밀번호로 로그인하고 토큰을 발급한다.
     * 기존 리프레시 토큰은 모두 삭제 후 재발급한다.
     *
     * @param request 로그인 요청 (이메일, 비밀번호)
     * @return 발급된 액세스 토큰 및 리프레시 토큰
     * @throws com.peekcart.user.domain.exception.UserException 인증 실패 시 {@code USR-002}
     */
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UserException(ErrorCode.USR_002));
        if (!user.matchesPassword(request.password(), passwordEncoder)) {
            throw new UserException(ErrorCode.USR_002);
        }
        refreshTokenRepository.deleteByUserId(user.getId());
        return issueTokens(user);
    }

    /**
     * 액세스 토큰을 블랙리스트에 등록하고 모든 리프레시 토큰을 삭제한다.
     *
     * @param accessToken 로그아웃할 액세스 토큰
     * @throws com.peekcart.user.domain.exception.UserException 유효하지 않은 토큰이면 {@code USR-004}
     */
    public void logout(String accessToken) {
        if (!jwtProvider.isValid(accessToken)) {
            throw new UserException(ErrorCode.USR_004);
        }
        Claims claims = jwtProvider.parseToken(accessToken);
        long ttlSeconds = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
        if (ttlSeconds > 0) {
            tokenBlacklistRepository.addToBlacklist(accessToken, ttlSeconds);
        }
        Long userId = Long.parseLong(claims.getSubject());
        refreshTokenRepository.deleteByUserId(userId);
    }

    /**
     * 리프레시 토큰으로 새 액세스/리프레시 토큰을 발급한다.
     * 기존 토큰이 DB에 없으면 그레이스 피리어드를 통해 재발급을 시도한다.
     *
     * @param oldRefreshToken 기존 리프레시 토큰
     * @return 새로 발급된 액세스 토큰 및 리프레시 토큰
     * @throws com.peekcart.user.domain.exception.UserException 토큰이 유효하지 않으면 {@code USR-004}
     */
    public TokenResponse refresh(String oldRefreshToken) {
        return refreshTokenRepository.findByToken(oldRefreshToken)
                .map(token -> rotateToken(token, oldRefreshToken))
                .orElseGet(() -> refreshViaGracePeriod(oldRefreshToken));
    }

    /**
     * DB에 유효한 리프레시 토큰이 있을 때 토큰을 로테이션하고 새 토큰을 발급한다.
     * 구 토큰은 그레이스 피리어드 10초 동안 Redis에 보관된다.
     */
    private TokenResponse rotateToken(RefreshToken token, String oldRefreshToken) {
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new UserException(ErrorCode.USR_003));
        tokenBlacklistRepository.addGracePeriod(oldRefreshToken, user.getId(), 10);
        refreshTokenRepository.deleteByToken(oldRefreshToken);
        return issueTokens(user);
    }

    /**
     * 그레이스 피리어드 내 재시도인 경우 userId를 조회하여 새 토큰을 발급한다.
     * 그레이스 피리어드도 만료됐으면 {@code USR-004} 예외를 던진다.
     */
    private TokenResponse refreshViaGracePeriod(String oldRefreshToken) {
        Long userId = tokenBlacklistRepository.getGracePeriodUserId(oldRefreshToken)
                .orElseThrow(() -> new UserException(ErrorCode.USR_004));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserException(ErrorCode.USR_003));
        return issueTokens(user);
    }

    /**
     * 액세스 토큰과 리프레시 토큰을 생성하고 리프레시 토큰을 DB에 저장한다.
     */
    private TokenResponse issueTokens(User user) {
        String accessToken = jwtProvider.createAccessToken(user.getId(), user.getRole().name());
        String refreshTokenValue = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(refreshTokenExpiry / 1000);
        refreshTokenRepository.save(RefreshToken.create(user.getId(), refreshTokenValue, expiresAt));
        return new TokenResponse(accessToken, refreshTokenValue);
    }
}
