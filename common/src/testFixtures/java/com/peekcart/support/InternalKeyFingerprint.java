package com.peekcart.support;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.HexFormat;

/**
 * 공개키의 정규화된 지문 (ADR-0017 D3 · 계획 loop3 #5).
 *
 * <p>키 도메인 분리를 <b>kid 로 검사하면 우회된다</b> — 같은 Gateway 공개키를 다른 kid 로 User JWKS 설정에
 * 끼워 넣으면 이름 대조는 통과하면서 키는 그대로 노출된다. PEM 문자열 hash 도 부족하다(개행·줄바꿈·
 * 재인코딩만 달라도 다른 값이 된다). 그래서 <b>SPKI DER 의 SHA-256</b> 을 쓴다 — 같은 키는 어떤 표현으로
 * 들어와도 같은 지문이 된다.
 */
public final class InternalKeyFingerprint {

    private InternalKeyFingerprint() {
    }

    /** SPKI DER SHA-256 (소문자 hex). */
    public static String of(PublicKey key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("공개키 fingerprint 계산 실패", e);
        }
    }
}
