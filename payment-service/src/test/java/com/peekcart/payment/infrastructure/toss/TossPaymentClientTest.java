package com.peekcart.payment.infrastructure.toss;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Toss 취소/조회 계약 테스트 (계획 P5).
 *
 * <p>실 API 는 승인된 실거래가 있어야 호출할 수 있어 검증 불가다(ADR-0018 Consequences).
 * 여기서 고정하는 것은 <b>우리 쪽 계약</b> — 오류 분류와 멱등키 전송이다.
 */
@DisplayName("TossPaymentClient 취소/조회 계약")
class TossPaymentClientTest {

    private static final String PAYMENT_KEY = "toss-key-1";
    private static final String CANCEL_URL = "https://api.tosspayments.com/v1/payments/toss-key-1/cancel";
    private static final String FIND_URL = "https://api.tosspayments.com/v1/payments/toss-key-1";

    private MockRestServiceServer server;
    private TossPaymentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentClient("test-secret", builder, new ObjectMapper());
    }

    @Test
    @DisplayName("2xx → SUCCEEDED, 그리고 Idempotency-Key 헤더가 전송된다")
    void success_sendsIdempotencyKey() {
        server.expect(requestTo(CANCEL_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "refund-7"))
                .andRespond(withSuccess("{\"status\":\"CANCELED\"}", MediaType.APPLICATION_JSON));

        TossOutcome outcome = client.cancel(PAYMENT_KEY, "주문 보상 환불", "refund-7");

        assertThat(outcome.kind()).isEqualTo(TossOutcome.Kind.SUCCEEDED);
        server.verify();
    }

    @Test
    @DisplayName("5xx → TRANSIENT (재시도 대상)")
    void serverError_isTransient() {
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("{\"code\":\"SERVER_ERROR\"}").contentType(MediaType.APPLICATION_JSON));

        assertThat(client.cancel(PAYMENT_KEY, "이유", "refund-7").kind())
                .isEqualTo(TossOutcome.Kind.TRANSIENT);
    }

    @Test
    @DisplayName("4xx → PERMANENT_FAILURE (재시도해도 상태가 바뀌지 않는다)")
    void clientError_isPermanent() {
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"code\":\"NOT_CANCELABLE_PAYMENT\"}").contentType(MediaType.APPLICATION_JSON));

        TossOutcome outcome = client.cancel(PAYMENT_KEY, "이유", "refund-7");

        assertThat(outcome.kind()).isEqualTo(TossOutcome.Kind.PERMANENT_FAILURE);
        assertThat(outcome.code()).isEqualTo("NOT_CANCELABLE_PAYMENT");
    }

    @Test
    @DisplayName("ALREADY_CANCELED_PAYMENT 는 실패가 아니라 별도 분류다 (조회로 진실을 확정하기 위함)")
    void alreadyCanceled_isSeparateKind() {
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"code\":\"ALREADY_CANCELED_PAYMENT\"}").contentType(MediaType.APPLICATION_JSON));

        assertThat(client.cancel(PAYMENT_KEY, "이유", "refund-7").kind())
                .isEqualTo(TossOutcome.Kind.ALREADY_CANCELED);
    }

    @Test
    @DisplayName("조회: cancels[] 합계가 전액이면 fullyCanceled")
    void find_sumsCancelAmounts() {
        server.expect(requestTo(FIND_URL))
                .andRespond(withSuccess("""
                        {"status":"CANCELED","cancels":[{"cancelAmount":30000},{"cancelAmount":20000}]}
                        """, MediaType.APPLICATION_JSON));

        Optional<TossPaymentSnapshot> snapshot = client.find(PAYMENT_KEY);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().canceledAmount()).isEqualTo(50_000L);
        assertThat(snapshot.get().isFullyCanceled(50_000L)).isTrue();
        assertThat(snapshot.get().isFullyCanceled(80_000L)).isFalse();
    }

    @Test
    @DisplayName("조회 실패는 empty — 진실을 모르는 상태를 성공/실패로 단정하지 않는다")
    void find_failureReturnsEmpty() {
        server.expect(requestTo(FIND_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(client.find(PAYMENT_KEY)).isEmpty();
    }
}
