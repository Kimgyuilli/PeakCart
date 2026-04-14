package com.peekcart.global.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MdcRecordInterceptor — traceId fallback 및 MDC 정리")
class MdcRecordInterceptorTest {

    private final MdcPayloadExtractor extractor = new MdcPayloadExtractor(new ObjectMapper());
    private final MdcRecordInterceptor interceptor = new MdcRecordInterceptor(extractor);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("헤더 X-Trace-Id 가 있으면 traceId 로 사용한다")
    void traceId_from_header() {
        ConsumerRecord<String, String> record = recordWith(
                "{\"eventId\":\"evt-1\",\"payload\":{\"userId\":42,\"orderId\":7}}");
        record.headers().add(KafkaTraceHeaders.TRACE_ID, "header-trace".getBytes(StandardCharsets.UTF_8));

        interceptor.intercept(record, null);

        assertThat(MDC.get("traceId")).isEqualTo("header-trace");
        assertThat(MDC.get("userId")).isEqualTo("42");
        assertThat(MDC.get("orderId")).isEqualTo("7");
    }

    @Test
    @DisplayName("헤더가 없으면 payload.eventId 를 traceId 로 사용한다 (재시도/DLQ 경로 묶기 위함)")
    void traceId_falls_back_to_eventId() {
        ConsumerRecord<String, String> record = recordWith(
                "{\"eventId\":\"evt-abc\",\"payload\":{\"orderId\":99}}");

        interceptor.intercept(record, null);

        assertThat(MDC.get("traceId")).isEqualTo("evt-abc");
        assertThat(MDC.get("orderId")).isEqualTo("99");
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    @DisplayName("헤더도 eventId 도 없으면 신규 UUID 16자리를 traceId 로 발급한다")
    void traceId_falls_back_to_uuid_when_payload_invalid() {
        ConsumerRecord<String, String> record = recordWith("not a json");

        interceptor.intercept(record, null);

        String traceId = MDC.get("traceId");
        assertThat(traceId).isNotNull().hasSize(16).matches("[0-9a-f]{16}");
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("orderId")).isNull();
    }

    @Test
    @DisplayName("payload 의 userId/orderId 가 없으면 MDC 에 설정하지 않는다")
    void optional_fields_skipped_when_absent() {
        ConsumerRecord<String, String> record = recordWith(
                "{\"eventId\":\"evt-2\",\"payload\":{\"orderNumber\":\"O-1\"}}");

        interceptor.intercept(record, null);

        assertThat(MDC.get("traceId")).isEqualTo("evt-2");
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("orderId")).isNull();
    }

    @Test
    @DisplayName("success / failure / afterRecord 어느 hook 에서도 MDC 가 비워진다")
    void mdc_cleared_after_record() {
        ConsumerRecord<String, String> record = recordWith(
                "{\"eventId\":\"evt-3\",\"payload\":{\"userId\":1,\"orderId\":2}}");
        interceptor.intercept(record, null);
        assertThat(MDC.get("traceId")).isNotNull();

        interceptor.success(record, null);
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("orderId")).isNull();

        interceptor.intercept(record, null);
        interceptor.failure(record, new RuntimeException("boom"), null);
        assertThat(MDC.get("traceId")).isNull();

        interceptor.intercept(record, null);
        interceptor.afterRecord(record, null);
        assertThat(MDC.get("traceId")).isNull();
    }

    private static ConsumerRecord<String, String> recordWith(String value) {
        return new ConsumerRecord<>(
                "test.topic", 0, 0L, 0L, TimestampType.NO_TIMESTAMP_TYPE,
                0, value == null ? 0 : value.length(),
                null, value, new RecordHeaders(), java.util.Optional.empty());
    }
}
