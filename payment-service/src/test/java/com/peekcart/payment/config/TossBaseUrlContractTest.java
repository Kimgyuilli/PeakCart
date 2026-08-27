package com.peekcart.payment.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG endpoint 설정 소유 계약 (계획 P1 · ADR-0007 · [SAGA-P1-BASEURL-OWN]).
 *
 * <p>{@code base-url} 은 운영(실 PG)·로컬·E2E(stub) 로 값이 갈리는 <b>연결 정보</b>다.
 * 두 가지를 동시에 고정한다:
 * <ol>
 *   <li>base 의 기본값은 <b>도달 불가 sentinel</b> — 설정 누락이 조용히 실 PG 로 나가면 안 된다</li>
 *   <li>{@code k8s} 프로파일은 <b>기본값 없이 강제</b> — 운영에서 sentinel 이 쓰이면 환불이 전부 실패한다</li>
 * </ol>
 *
 * <p>YAML 을 직접 읽는다. 스프링 컨텍스트로는 "base 가 무엇을 기본값으로 선언했나" 를 볼 수 없다 —
 * 어떤 값이든 해석되고 나면 출처가 지워지기 때문이다.
 */
@DisplayName("[SAGA-P1-BASEURL-OWN] toss base-url 설정 소유 계약")
class TossBaseUrlContractTest {

    private static final Pattern BASE_URL_LINE =
            Pattern.compile("^\\s*base-url:\\s*(.+?)\\s*$", Pattern.MULTILINE);

    private static String declaration(String resource) throws IOException {
        Path path = Path.of("src/main/resources", resource);
        String text = Files.readString(path, StandardCharsets.UTF_8);
        Matcher m = BASE_URL_LINE.matcher(text);
        assertThat(m.find()).as("%s 에 base-url 선언이 있어야 한다", resource).isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("[SAGA-P1-BASEURL-OWN] base application.yml 의 기본값은 실 PG 호스트가 아닌 도달 불가 sentinel")
    void baseDefaultIsUnroutableSentinel() throws IOException {
        String decl = declaration("application.yml");

        assertThat(decl)
                .as("환경변수 참조 형태여야 한다")
                .startsWith("${TOSS_BASE_URL:");

        String fallback = decl.substring("${TOSS_BASE_URL:".length(), decl.length() - 1);
        assertThat(fallback).as("기본값이 비어 있으면 상대 URL 로 조용히 깨진다").isNotBlank();

        URI uri = URI.create(fallback);
        assertThat(uri.getHost())
                .as("설정 누락이 실 PG 로 새면 안 된다 — 기본값 호스트: %s", uri.getHost())
                .isNotEqualTo("api.tosspayments.com");
        assertThat(uri.getHost()).isEqualTo("localhost");
        assertThat(uri.getPort()).as("discard 포트(9) — 나가더라도 아무 데도 닿지 않는다").isEqualTo(9);
    }

    @Test
    @DisplayName("[SAGA-P1-BASEURL-OWN] application-k8s.yml 은 기본값 없이 강제한다 — 운영 fail-fast")
    void k8sProfileHasNoFallback() throws IOException {
        String decl = declaration("application-k8s.yml");

        assertThat(decl)
                .as("k8s 는 기본값이 있으면 sentinel 이 운영에 새어 환불이 전부 실패한다")
                .isEqualTo("${TOSS_BASE_URL}");
    }

    @Test
    @DisplayName("[SAGA-P1-BASEURL-OWN] local 프로파일은 명시 endpoint 를 갖는다 — 어느 환경도 sentinel 에 의존하지 않는다")
    void localProfileDeclaresEndpoint() throws IOException {
        assertThat(declaration("application-local.yml")).isEqualTo("https://api.tosspayments.com/v1");
    }
}
