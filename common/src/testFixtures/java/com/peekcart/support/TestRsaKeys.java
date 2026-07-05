package com.peekcart.support;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * 테스트 전용 RS256 키쌍을 <b>런타임에 생성</b>하는 공유 헬퍼 (구현 ③ PR1 보안 보정).
 * <p>키 자료(특히 개인키)를 저장소에 커밋하지 않기 위해(ADR-0013 D2), JVM 기동 시 한 번
 * 임시 디렉토리에 PEM 을 생성하고 각 테스트가 {@code file:} 경로로 참조한다. 산출물·형상관리에
 * 개인키가 남지 않는다.
 * <ul>
 *   <li>단위 테스트: {@link #privateKeyFile()}/{@link #publicKeyFile()} 로 {@code JwtKeyProperties} 를 직접 구성.</li>
 *   <li>{@code @SpringBootTest}: {@link #register(DynamicPropertyRegistry)} 를 {@code @DynamicPropertySource} 에서 호출.</li>
 * </ul>
 */
public final class TestRsaKeys {

    public static final String KID = "test-2026";

    private static Path privateKey;
    private static Path publicKey;

    private TestRsaKeys() {
    }

    /** {@code @DynamicPropertySource} 에서 호출 — 서명/검증 키를 임시 생성 키쌍으로 오버라이드한다. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.rs256.active-kid", () -> KID);
        registry.add("app.jwt.rs256.private-key-location", () -> "file:" + privateKeyFile());
        registry.add("app.jwt.rs256.public-keys[0].kid", () -> KID);
        registry.add("app.jwt.rs256.public-keys[0].location", () -> "file:" + publicKeyFile());
    }

    public static synchronized Path privateKeyFile() {
        ensureGenerated();
        return privateKey;
    }

    public static synchronized Path publicKeyFile() {
        ensureGenerated();
        return publicKey;
    }

    private static synchronized void ensureGenerated() {
        if (privateKey != null) {
            return;
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            Path dir = Files.createTempDirectory("peekcart-test-rsa");
            privateKey = writePem(dir.resolve("private.pem"), "PRIVATE KEY", pair.getPrivate().getEncoded());
            publicKey = writePem(dir.resolve("public.pem"), "PUBLIC KEY", pair.getPublic().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("테스트 RSA 키쌍 생성 실패", e);
        }
    }

    private static Path writePem(Path path, String type, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        String pem = "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
        Files.writeString(path, pem);
        return path;
    }
}
