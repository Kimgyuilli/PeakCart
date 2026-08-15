package com.peekcart.payment.infrastructure.toss;

import com.peekcart.payment.application.RefundOutcome;
import com.peekcart.payment.application.RefundProperties;
import com.peekcart.payment.domain.model.PaymentRefund;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 환불 실행 규약 테스트 (ADR-0018 D3 crash matrix · D5).
 *
 * <p>여기서 고정하는 것은 <b>호출 순서와 분기</b>다 — "조회 없이 재호출하지 않는다"와
 * "모든 재시도가 같은 멱등키를 쓴다"는 코드를 읽어서는 회귀를 못 막는다.
 */
@DisplayName("RefundExecutor 호출 규약")
class RefundExecutorTest {

    private static final long AMOUNT = 50_000L;

    private TossPaymentClient client;
    private RefundExecutor executor;
    private PaymentRefund refund;

    @BeforeEach
    void setUp() {
        client = mock(TossPaymentClient.class);
        RefundProperties properties = new RefundProperties();
        properties.setMaxAttempts(3);
        properties.setRetryBackoff(Duration.ofMillis(1));
        properties.setPgTimeout(Duration.ofSeconds(10));
        properties.setClaimLease(Duration.ofMinutes(5));
        properties.setUnresolvedLimit(Duration.ofHours(24));
        properties.setBatchSize(5);
        properties.setMaxBatchesPerRun(2);
        properties.setLockAtMostFor(Duration.ofMinutes(20));
        executor = new RefundExecutor(client, properties);

        refund = mock(PaymentRefund.class);
        given(refund.getOrderId()).willReturn(7L);
        given(refund.getPaymentKey()).willReturn("toss-key-7");
        given(refund.getAmount()).willReturn(AMOUNT);
    }

    @Test
    @DisplayName("transient 3회 소진 → UNRESOLVED, 모든 시도가 동일 멱등키를 쓴다")
    void transientRetries_useSameIdempotencyKey() {
        given(client.cancel(anyString(), anyString(), anyString()))
                .willReturn(TossOutcome.transient_("SERVER_ERROR", "{}"));

        RefundExecutor.CallResult result = executor.execute(refund);

        assertThat(result.outcome().kind()).isEqualTo(RefundOutcome.Kind.UNRESOLVED);
        assertThat(result.attempts()).isEqualTo(3);
        verify(client, times(3)).cancel(eq("toss-key-7"), anyString(), eq("refund-7"));
    }

    @Test
    @DisplayName("4xx 에 code 가 없어도 failureCode 는 null 이 아니다 (회신 필수 필드)")
    void permanentFailureWithoutCode_hasFallbackCode() {
        given(client.cancel(anyString(), anyString(), anyString()))
                .willReturn(TossOutcome.permanentFailure("HTTP_400", ""));

        RefundOutcome outcome = executor.execute(refund).outcome();

        assertThat(outcome.kind()).isEqualTo(RefundOutcome.Kind.FAILED);
        assertThat(outcome.code()).isNotBlank();
    }

    @Test
    @DisplayName("ALREADY_CANCELED + 전액 취소 확인 → SUCCEEDED (조회로 확정한다)")
    void alreadyCanceled_fullAmount_succeeds() {
        given(client.cancel(anyString(), anyString(), anyString()))
                .willReturn(TossOutcome.alreadyCanceled("{}"));
        given(client.find("toss-key-7"))
                .willReturn(Optional.of(new TossPaymentSnapshot("CANCELED", AMOUNT, "{}")));

        assertThat(executor.execute(refund).outcome().kind()).isEqualTo(RefundOutcome.Kind.SUCCEEDED);
    }

    @Test
    @DisplayName("ALREADY_CANCELED + 금액 불일치 → FAILED (부분·초과 취소를 성공으로 보지 않는다)")
    void alreadyCanceled_amountMismatch_fails() {
        given(client.cancel(anyString(), anyString(), anyString()))
                .willReturn(TossOutcome.alreadyCanceled("{}"));
        given(client.find("toss-key-7"))
                .willReturn(Optional.of(new TossPaymentSnapshot("CANCELED", 10_000L, "{}")));

        RefundOutcome outcome = executor.execute(refund).outcome();

        assertThat(outcome.kind()).isEqualTo(RefundOutcome.Kind.FAILED);
        assertThat(outcome.code()).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("ALREADY_CANCELED + 조회 실패 → UNRESOLVED (모르는 상태를 성공으로 단정하지 않는다)")
    void alreadyCanceled_queryFails_unresolved() {
        given(client.cancel(anyString(), anyString(), anyString()))
                .willReturn(TossOutcome.alreadyCanceled("{}"));
        given(client.find("toss-key-7")).willReturn(Optional.empty());

        assertThat(executor.execute(refund).outcome().kind()).isEqualTo(RefundOutcome.Kind.UNRESOLVED);
    }

    @Test
    @DisplayName("확정 경로는 조회를 먼저 한다 — 이미 취소됐으면 cancel 을 호출하지 않는다 (crash matrix b)")
    void verifyThenExecute_alreadyCanceled_doesNotRecall() {
        given(client.find("toss-key-7"))
                .willReturn(Optional.of(new TossPaymentSnapshot("CANCELED", AMOUNT, "{}")));

        RefundExecutor.CallResult result = executor.verifyThenExecute(refund);

        assertThat(result.outcome().kind()).isEqualTo(RefundOutcome.Kind.SUCCEEDED);
        verify(client, never()).cancel(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("확정 경로: 미취소로 확정되면 그때만 동일 멱등키로 재호출한다 (조회 → 취소 순서)")
    void verifyThenExecute_notCanceled_recallsAfterQuery() {
        given(client.find("toss-key-7"))
                .willReturn(Optional.of(new TossPaymentSnapshot("DONE", 0L, "{}")));
        given(client.cancel(anyString(), anyString(), anyString()))
                .willReturn(TossOutcome.succeeded("{}"));

        RefundExecutor.CallResult result = executor.verifyThenExecute(refund);

        assertThat(result.outcome().kind()).isEqualTo(RefundOutcome.Kind.SUCCEEDED);
        var order = inOrder(client);
        order.verify(client).find("toss-key-7");
        order.verify(client).cancel(eq("toss-key-7"), anyString(), eq("refund-7"));
    }

    @Test
    @DisplayName("확정 경로: 조회 실패면 재호출하지 않고 UNRESOLVED 로 남긴다")
    void verifyThenExecute_queryFails_doesNotCall() {
        given(client.find("toss-key-7")).willReturn(Optional.empty());

        assertThat(executor.verifyThenExecute(refund).outcome().kind())
                .isEqualTo(RefundOutcome.Kind.UNRESOLVED);
        verify(client, never()).cancel(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("확정 경로: 부분 취소는 재호출 없이 FAILED (중복 환불 방지)")
    void verifyThenExecute_partialCancel_fails() {
        given(client.find("toss-key-7"))
                .willReturn(Optional.of(new TossPaymentSnapshot("PARTIAL_CANCELED", 20_000L, "{}")));

        RefundOutcome outcome = executor.verifyThenExecute(refund).outcome();

        assertThat(outcome.kind()).isEqualTo(RefundOutcome.Kind.FAILED);
        assertThat(outcome.code()).isEqualTo("AMOUNT_MISMATCH");
        verify(client, never()).cancel(anyString(), anyString(), anyString());
    }
}
