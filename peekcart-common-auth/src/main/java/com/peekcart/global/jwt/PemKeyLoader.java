package com.peekcart.global.jwt;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * PEM(PKCS#8 개인키 / SPKI 공개키) 자료를 RSA 키 객체로 로딩하는 유틸.
 * jjwt/보안 컴포넌트가 서명·검증에 쓰는 {@link RSAPrivateKey}/{@link RSAPublicKey} 로 변환한다.
 */
public final class PemKeyLoader {

    private PemKeyLoader() {
    }

    /** PKCS#8 PEM 개인키를 로드한다. */
    public static RSAPrivateKey loadPrivateKey(Resource location) {
        byte[] der = der(location, "PRIVATE KEY");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("개인키 로드 실패: " + describe(location), e);
        }
    }

    /** SPKI PEM 공개키를 로드한다. */
    public static RSAPublicKey loadPublicKey(Resource location) {
        byte[] der = der(location, "PUBLIC KEY");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("공개키 로드 실패: " + describe(location), e);
        }
    }

    private static byte[] der(Resource location, String type) {
        try (InputStream in = location.getInputStream()) {
            String pem = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String base64 = pem
                    .replace("-----BEGIN " + type + "-----", "")
                    .replace("-----END " + type + "-----", "")
                    .replaceAll("\\s", "");
            return Base64.getDecoder().decode(base64);
        } catch (IOException e) {
            throw new IllegalStateException("키 자료 읽기 실패: " + describe(location), e);
        }
    }

    private static String describe(Resource location) {
        return location == null ? "(null)" : location.getDescription();
    }
}
