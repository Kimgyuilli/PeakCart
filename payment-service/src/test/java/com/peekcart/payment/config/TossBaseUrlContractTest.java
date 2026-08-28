package com.peekcart.payment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.payment.infrastructure.toss.TossPaymentClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.web.client.RestClient;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
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

    /**
     * YAML 문자열 검사는 <b>선언</b>만 본다 — 프로파일 병합·placeholder 해석·빈 생성 경로가
     * 바뀌어도 통과한다. 실제 해석 결과를 컨텍스트로 확인한다(리뷰 #6).
     */
    @Nested
    @DisplayName("[SAGA-P1-BASEURL-BOOT] 해석 결과")
    class Resolution {

        /** ConfigData 초기화를 붙여 **실제 application.yml + 프로파일 병합**을 태운다. */
        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                // 실행 환경에 TOSS_BASE_URL 이 떠 있으면 sentinel/fail-fast 검사가 그 값으로
                // 조용히 통과한다 — 계약이 아니라 개발자·CI 환경을 검사하게 된다.
                // systemEnvironment 를 그 키만 뺀 사본으로 교체해 격리한다.
                .withInitializer(TossBaseUrlContractTest::stripAmbientBaseUrl)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PlaceholderSupport.class, ClientOnly.class);

        /**
         * <b>base 의 기본 활성 프로파일은 {@code local} 이다</b>({@code spring.profiles.active: local}).
         * 따라서 아무 것도 지정하지 않고 뜨면 sentinel 이 아니라 <b>local 의 운영 URL</b> 이 이긴다 —
         * 이건 로컬 개발자가 테스트 키로 Toss sandbox 를 쓰는 의도된 동작이다.
         * sentinel 은 "local 도 k8s 도 아닌 프로파일에서 base-url 을 안 준 경우" 의 안전망이다.
         * 이 사실을 테스트가 잡아냈다(초안은 sentinel 이 기본이라고 잘못 적었다).
         */
        @Test
        @DisplayName("[SAGA-P1-BASEURL-BOOT] 기본 활성 프로파일 local 에서는 local 의 endpoint 가 이긴다")
        void defaultProfileIsLocal() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment().getProperty("toss.payments.base-url"))
                        .isEqualTo("https://api.tosspayments.com/v1");
            });
        }

        @Test
        @DisplayName("[SAGA-P1-BASEURL-BOOT] base-url 을 주지 않는 프로파일에서는 도달 불가 sentinel 이 해석된다")
        void unknownProfile_resolvesSentinel() {
            runner.withPropertyValues("spring.profiles.active=e2e-unknown")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getEnvironment().getProperty("toss.payments.base-url"))
                                .isEqualTo("http://localhost:9/toss-base-url-not-configured");
                    });
        }

        @Test
        @DisplayName("[SAGA-P1-BASEURL-BOOT] k8s 프로파일에서 TOSS_BASE_URL 미주입이면 부팅이 실패한다")
        void k8sProfileWithoutEnv_failsFast() {
            runner.withPropertyValues("spring.profiles.active=k8s",
                            "TOSS_SECRET_KEY=x", "TOSS_WEBHOOK_SECRET=y")
                    .run(context -> assertThat(context).hasFailed());
        }

        @Test
        @DisplayName("[SAGA-P1-BASEURL-BOOT] k8s 프로파일에 값을 주면 그 값이 해석된다 — 앞 검사가 다른 이유로 실패한 게 아니다")
        void k8sProfileWithEnv_resolves() {
            runner.withPropertyValues("spring.profiles.active=k8s",
                            "TOSS_SECRET_KEY=x", "TOSS_WEBHOOK_SECRET=y",
                            "TOSS_BASE_URL=http://pg-stub:8080/v1")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getEnvironment().getProperty("toss.payments.base-url"))
                                .isEqualTo("http://pg-stub:8080/v1");
                    });
        }
    }

    private static void stripAmbientBaseUrl(ConfigurableApplicationContext context) {
        MutablePropertySources sources = context.getEnvironment().getPropertySources();
        String name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        PropertySource<?> original = sources.get(name);
        if (original == null) {
            return;
        }
        Map<String, Object> copy = new HashMap<>(((Map<?, ?>) original.getSource()).entrySet().stream()
                .collect(HashMap::new, (m, e) -> m.put(String.valueOf(e.getKey()), e.getValue()), HashMap::putAll));
        copy.remove("TOSS_BASE_URL");
        copy.remove("toss.payments.base-url");
        // SystemEnvironmentPropertySource 로 되돌린다 — 평범한 MapPropertySource 로 교체하면
        // 환경변수 이름 완화 매핑(TOSS_X ↔ toss.x)이 사라져 다른 프로퍼티 해석까지 바뀐다.
        sources.replace(name, new SystemEnvironmentPropertySource(name, copy));
    }

    @Configuration(proxyBeanMethods = false)
    static class PlaceholderSupport {
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ClientOnly {
        @Bean
        TossPaymentClient tossPaymentClient(
                @Value("${toss.payments.secret-key}") String secretKey,
                @Value("${toss.payments.base-url}") String baseUrl) {
            return new TossPaymentClient(secretKey, baseUrl, RestClient.builder(), new ObjectMapper());
        }
    }
}
