package com.peekcart.user.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.jwt.JwtProvider;
import com.peekcart.user.domain.RefreshToken;
import com.peekcart.user.domain.RefreshTokenRepository;
import com.peekcart.user.domain.User;
import com.peekcart.user.domain.UserException;
import com.peekcart.user.domain.UserRepository;
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

    public TokenResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new UserException(ErrorCode.USR_001);
        }
        String passwordHash = passwordEncoder.encode(request.password());
        User user = userRepository.save(User.create(request.email(), passwordHash, request.name()));
        return issueTokens(user);
    }

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UserException(ErrorCode.USR_002));
        if (!user.matchesPassword(request.password(), passwordEncoder)) {
            throw new UserException(ErrorCode.USR_002);
        }
        refreshTokenRepository.deleteByUserId(user.getId());
        return issueTokens(user);
    }

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

    public TokenResponse refresh(String oldRefreshToken) {
        return refreshTokenRepository.findByToken(oldRefreshToken)
                .map(token -> rotateToken(token, oldRefreshToken))
                .orElseGet(() -> refreshViaGracePeriod(oldRefreshToken));
    }

    private TokenResponse rotateToken(RefreshToken token, String oldRefreshToken) {
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new UserException(ErrorCode.USR_003));
        tokenBlacklistRepository.addGracePeriod(oldRefreshToken, user.getId(), 10);
        refreshTokenRepository.deleteByToken(oldRefreshToken);
        return issueTokens(user);
    }

    private TokenResponse refreshViaGracePeriod(String oldRefreshToken) {
        Long userId = tokenBlacklistRepository.getGracePeriodUserId(oldRefreshToken)
                .orElseThrow(() -> new UserException(ErrorCode.USR_004));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserException(ErrorCode.USR_003));
        return issueTokens(user);
    }

    private TokenResponse issueTokens(User user) {
        String accessToken = jwtProvider.createAccessToken(user.getId(), user.getRole().name());
        String refreshTokenValue = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(refreshTokenExpiry / 1000);
        refreshTokenRepository.save(RefreshToken.create(user.getId(), refreshTokenValue, expiresAt));
        return new TokenResponse(accessToken, refreshTokenValue);
    }
}
