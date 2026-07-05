package com.peekcart.user.presentation;

import com.peekcart.global.jwt.RsaPublicKeyRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * JWKS(JSON Web Key Set) 공개키 배포 엔드포인트 (ADR-0013 D1).
 * User(발급 서버)가 서명 공개키를 {@code kid} 로 노출하고, Gateway/서비스 검증기가 이를 받아 RS256 을 검증한다.
 * 표준 경로 {@code /.well-known/jwks.json} 으로 노출하며 인증 면제(공개)다.
 */
@Tag(name = "JWKS", description = "RS256 공개키 배포")
@RestController
@RequiredArgsConstructor
public class JwkController {

    private final RsaPublicKeyRegistry publicKeyRegistry;

    @Operation(summary = "JWKS 공개키", description = "RS256 서명 검증용 공개키 집합(kid 별)을 반환한다.")
    @GetMapping("/.well-known/jwks.json")
    public Map<String, List<Map<String, String>>> jwks() {
        List<Map<String, String>> keys = publicKeyRegistry.all().entrySet().stream()
                .map(e -> toJwk(e.getKey(), e.getValue()))
                .toList();
        return Map.of("keys", keys);
    }

    private Map<String, String> toJwk(String kid, RSAPublicKey key) {
        return Map.of(
                "kty", "RSA",
                "use", "sig",
                "alg", "RS256",
                "kid", kid,
                "n", base64Url(key.getModulus()),
                "e", base64Url(key.getPublicExponent())
        );
    }

    /** JWK 는 부호 없는 big-endian(선행 0 바이트 제거) 을 base64url(no padding)로 인코딩한다. */
    private String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
