package com.peekcart.payment.infrastructure.toss;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Toss Payments API 클라이언트.
 * RestClient(Spring 6.1+)를 사용하며 Basic Auth 방식으로 인증한다.
 */
@Slf4j
@Component
public class TossPaymentClient {

    private static final String ALREADY_CANCELED = "ALREADY_CANCELED_PAYMENT";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * @param baseUrl PG endpoint. 운영/로컬/E2E 가 서로 다른 값을 쓰는 <b>연결 정보</b>다(ADR-0007).
     *                base {@code application.yml} 은 <b>도달 불가 sentinel</b>(discard 포트)을 기본값으로
     *                두어 설정 누락이 실 PG 로 새지 않게 하고, {@code application-k8s.yml} 이
     *                {@code ${TOSS_BASE_URL}} 을 기본값 없이 강제해 운영에서 fail-fast 한다.
     * @param builder 타임아웃은 {@link TossClientConfig} 의 {@code RestClientCustomizer} 가 이미
     *                적용한 상태로 주입된다 — 여기서 {@code requestFactory} 를 다시 세팅하지 않는다
     *                (그러면 테스트의 {@code MockRestServiceServer} 바인딩까지 덮어쓴다)
     */
    public TossPaymentClient(@Value("${toss.payments.secret-key}") String secretKey,
                             @Value("${toss.payments.base-url}") String baseUrl,
                             RestClient.Builder builder,
                             ObjectMapper objectMapper) {
        String credentials = Base64.getEncoder().encodeToString((secretKey + ":").getBytes());
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Toss 결제 승인 API를 호출한다.
     *
     * @throws org.springframework.web.client.RestClientException Toss API 호출 실패 시
     */
    public TossConfirmResponse confirm(String paymentKey, String orderId, long amount) {
        return restClient.post()
                .uri("/payments/confirm")
                .body(Map.of(
                        "paymentKey", paymentKey,
                        "orderId", orderId,
                        "amount", amount
                ))
                .retrieve()
                .body(TossConfirmResponse.class);
    }

    /**
     * 결제를 취소(전액 환불)한다 (ADR-0018 D3/D5).
     *
     * <p>예외를 던지지 않고 {@link TossOutcome} 으로 분류해 돌려준다 — 호출자(dispatcher)는 이
     * 분류로 원장 상태를 정하며, 분류 자체는 외부 연동 지식이라 여기에 둔다.
     *
     * @param idempotencyKey 재시도 시 <b>동일한 값</b>이어야 한다. PG 측에서 중복 취소를 흡수한다
     */
    public TossOutcome cancel(String paymentKey, String cancelReason, String idempotencyKey) {
        try {
            return restClient.post()
                    .uri("/payments/{paymentKey}/cancel", paymentKey)
                    .header(IDEMPOTENCY_HEADER, idempotencyKey)
                    .body(Map.of("cancelReason", cancelReason))
                    .exchange((request, response) -> toOutcome(response.getStatusCode(), readBody(response)), false);
        } catch (Exception e) {
            // 연결 실패·타임아웃·응답 파싱 불가 — 취소가 성립했는지 알 수 없다
            log.warn("Toss 취소 호출 실패(결과 불명) — paymentKey={}", paymentKey, e);
            return TossOutcome.unknown(e.getMessage());
        }
    }

    /**
     * 결제를 조회한다 (ADR-0018 D3 — crash 복구의 진실 확정 수단).
     *
     * @return 조회 실패 시 empty. 성공 시 응답 원문
     */
    public Optional<TossPaymentSnapshot> find(String paymentKey) {
        try {
            String raw = restClient.get()
                    .uri("/payments/{paymentKey}", paymentKey)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(raw);
            long canceledAmount = 0L;
            JsonNode cancels = root.get("cancels");
            if (cancels != null && cancels.isArray()) {
                for (JsonNode cancel : cancels) {
                    JsonNode amount = cancel.get("cancelAmount");
                    canceledAmount += amount == null ? 0L : amount.asLong();
                }
            }
            String status = root.path("status").asText(null);
            return Optional.of(new TossPaymentSnapshot(status, canceledAmount, raw));
        } catch (Exception e) {
            log.warn("Toss 결제 조회 실패 — paymentKey={}", paymentKey, e);
            return Optional.empty();
        }
    }

    private TossOutcome toOutcome(HttpStatusCode status, String body) {
        if (status.is2xxSuccessful()) {
            return TossOutcome.succeeded(body);
        }
        String code = extractCode(body);
        if (ALREADY_CANCELED.equals(code)) {
            return TossOutcome.alreadyCanceled(body);
        }
        if (status.is5xxServerError() || status.value() == 429) {
            return TossOutcome.transient_(code, body);
        }
        // 4xx — 기간 초과·금액 불일치·인증 실패 등. 재시도해도 상태가 바뀌지 않는다.
        // code 가 없어도 null 을 그대로 두지 않는다 — 회신 payload 의 failureCode 는 실패 시 필수다(ADR-0018 D1).
        return TossOutcome.permanentFailure(code != null ? code : "HTTP_" + status.value(), body);
    }

    private String extractCode(String body) {
        try {
            return objectMapper.readTree(body).path("code").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String readBody(org.springframework.http.client.ClientHttpResponse response) {
        try (var is = response.getBody()) {
            return new String(is.readAllBytes());
        } catch (Exception e) {
            return "";
        }
    }
}
