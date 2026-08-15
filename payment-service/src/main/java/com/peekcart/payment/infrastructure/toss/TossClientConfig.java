package com.peekcart.payment.infrastructure.toss;

import com.peekcart.payment.application.RefundProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PG 호출 타임아웃 배선 (ADR-0018 D3).
 *
 * <p>타임아웃을 실제로 걸지 않으면 {@code app.refund.claim-lease > pg-timeout × 시도} 라는
 * 부팅 검증(RefundProperties)이 <b>근거 없는 계산</b>이 된다 — 호출이 무기한 늘어지면 lease 가 먼저
 * 만료돼 살아있는 호출을 다른 인스턴스가 회수한다. 그래서 같은 정책값을 실제 커넥션에 적용한다.
 *
 * <p>{@code RestClientCustomizer} 로 두는 이유: 클라이언트가 직접 {@code requestFactory} 를 세팅하면
 * 테스트의 {@code MockRestServiceServer} 바인딩까지 덮어써 계약 테스트가 불가능해진다.
 */
@Configuration
public class TossClientConfig {

    @Bean
    public RestClientCustomizer tossTimeoutCustomizer(RefundProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(properties.getPgTimeout())
                .withReadTimeout(properties.getPgTimeout());
        return builder -> builder.requestFactory(ClientHttpRequestFactories.get(settings));
    }
}
