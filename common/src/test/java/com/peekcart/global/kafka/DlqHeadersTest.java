package com.peekcart.global.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DlqHeaders — DLQ 레코드 판독 (계획 ④-c-2a P1)")
class DlqHeadersTest {

    private static final String DLQ_TOPIC = "payment.completed.dlq";
    private static final int DLQ_PARTITION = 2;
    private static final long DLQ_OFFSET = 77L;

    private RecordHeaders headers;

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>(DLQ_TOPIC, DLQ_PARTITION, DLQ_OFFSET, key, value);
    }

    private ConsumerRecord<String, String> recordWith(RecordHeaders h, String key, String value) {
        ConsumerRecord<String, String> r = record(key, value);
        h.forEach(header -> r.headers().add(header));
        return r;
    }

    private static RecordHeaders fullOriginHeaders() {
        RecordHeaders h = new RecordHeaders();
        h.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, "payment.completed".getBytes(StandardCharsets.UTF_8));
        h.add(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(Integer.BYTES).putInt(1).array());
        h.add(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(Long.BYTES).putLong(42L).array());
        h.add(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP,
                "order-svc-payment-completed-group".getBytes(StandardCharsets.UTF_8));
        h.add(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP, ByteBuffer.allocate(Long.BYTES).putLong(1_700_000_000_000L).array());
        h.add(KafkaHeaders.DLT_EXCEPTION_FQCN, "java.lang.IllegalArgumentException".getBytes(StandardCharsets.UTF_8));
        h.add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "eventId 필드가 없습니다".getBytes(StandardCharsets.UTF_8));
        return h;
    }

    @Nested
    @DisplayName("origin 헤더가 온전하면")
    class ResolvedOrigin {

        @Test
        @DisplayName("RESOLVED_ORIGIN 으로 원본 좌표를 싣는다")
        void resolvesOriginCoordinates() {
            DlqOrigin origin = DlqHeaders.parse(recordWith(fullOriginHeaders(), "order-1", "{}"));

            assertThat(origin.originKind()).isEqualTo(DlqOriginKind.RESOLVED_ORIGIN);
            assertThat(origin.originTopic()).isEqualTo("payment.completed");
            assertThat(origin.originPartition()).isEqualTo(1);
            assertThat(origin.originOffset()).isEqualTo(42L);
            assertThat(origin.failedConsumerGroup()).isEqualTo("order-svc-payment-completed-group");
            assertThat(origin.originalTimestamp()).isEqualTo(1_700_000_000_000L);
            assertThat(origin.exceptionType()).isEqualTo("java.lang.IllegalArgumentException");
            assertThat(origin.exceptionMessage()).isEqualTo("eventId 필드가 없습니다");
        }

        @Test
        @DisplayName("originalKey 는 헤더가 아니라 DLQ 레코드 자신의 key 에서 온다 (§2.4)")
        void readsOriginalKeyFromRecord() {
            DlqOrigin origin = DlqHeaders.parse(recordWith(fullOriginHeaders(), "order-99", "{}"));

            assertThat(origin.originalKey()).isEqualTo("order-99");
        }

        @Test
        @DisplayName("null key 도 받는다 — 원장은 nullable 로 보존한다")
        void allowsNullKey() {
            DlqOrigin origin = DlqHeaders.parse(recordWith(fullOriginHeaders(), null, "{}"));

            assertThat(origin.originalKey()).isNull();
        }
    }

    @Nested
    @DisplayName("origin 헤더가 판독 불가면")
    class DlqOriginFallback {

        @Test
        @DisplayName("헤더가 하나도 없으면 DLQ 레코드 자신의 좌표를 쓴다")
        void fallsBackToDlqCoordinates() {
            DlqOrigin origin = DlqHeaders.parse(record("k", "invalid-json-message"));

            assertThat(origin.originKind()).isEqualTo(DlqOriginKind.DLQ_ORIGIN);
            assertThat(origin.originTopic()).isEqualTo(DLQ_TOPIC);
            assertThat(origin.originPartition()).isEqualTo(DLQ_PARTITION);
            assertThat(origin.originOffset()).isEqualTo(DLQ_OFFSET);
        }

        @Test
        @DisplayName("topic 만 없어도 DLQ_ORIGIN 이다 — 좌표는 셋이 함께여야 의미가 있다")
        void requiresAllThreeCoordinates() {
            RecordHeaders h = fullOriginHeaders();
            h.remove(KafkaHeaders.DLT_ORIGINAL_TOPIC);

            DlqOrigin origin = DlqHeaders.parse(recordWith(h, "k", "{}"));

            assertThat(origin.originKind()).isEqualTo(DlqOriginKind.DLQ_ORIGIN);
            assertThat(origin.originTopic()).isEqualTo(DLQ_TOPIC);
        }

        @Test
        @DisplayName("partition 이 4바이트가 아니면 판독 실패로 본다 — getInt 예외를 던지지 않는다")
        void handlesMalformedPartitionWidth() {
            RecordHeaders h = fullOriginHeaders();
            h.remove(KafkaHeaders.DLT_ORIGINAL_PARTITION);
            h.add(KafkaHeaders.DLT_ORIGINAL_PARTITION, new byte[]{0x01});

            DlqOrigin origin = DlqHeaders.parse(recordWith(h, "k", "{}"));

            assertThat(origin.originKind()).isEqualTo(DlqOriginKind.DLQ_ORIGIN);
            assertThat(origin.originPartition()).isEqualTo(DLQ_PARTITION);
        }

        @Test
        @DisplayName("offset 이 8바이트가 아니어도 예외 없이 DLQ_ORIGIN 이 된다")
        void handlesMalformedOffsetWidth() {
            RecordHeaders h = fullOriginHeaders();
            h.remove(KafkaHeaders.DLT_ORIGINAL_OFFSET);
            h.add(KafkaHeaders.DLT_ORIGINAL_OFFSET, new byte[]{0x01, 0x02});

            DlqOrigin origin = DlqHeaders.parse(recordWith(h, "k", "{}"));

            assertThat(origin.originKind()).isEqualTo(DlqOriginKind.DLQ_ORIGIN);
            assertThat(origin.originOffset()).isEqualTo(DLQ_OFFSET);
        }
    }

    @Nested
    @DisplayName("consumer group 이 없으면")
    class UnknownGroup {

        @Test
        @DisplayName("sentinel 로 채운다 — NULL 은 UNIQUE 를 무력화한다 (§2.5)")
        void fillsSentinel() {
            RecordHeaders h = fullOriginHeaders();
            h.remove(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP);

            DlqOrigin origin = DlqHeaders.parse(recordWith(h, "k", "{}"));

            assertThat(origin.failedConsumerGroup()).isEqualTo(DlqOrigin.UNKNOWN_CONSUMER_GROUP);
            assertThat(origin.hasUnknownConsumerGroup()).isTrue();
        }

        @Test
        @DisplayName("빈 문자열도 sentinel 로 본다")
        void treatsBlankAsUnknown() {
            RecordHeaders h = fullOriginHeaders();
            h.remove(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP);
            h.add(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, "  ".getBytes(StandardCharsets.UTF_8));

            DlqOrigin origin = DlqHeaders.parse(recordWith(h, "k", "{}"));

            assertThat(origin.hasUnknownConsumerGroup()).isTrue();
        }

        @Test
        @DisplayName("group 이 판독되면 sentinel 이 아니다 — quarantine 대상이 아님")
        void resolvedGroupIsNotUnknown() {
            DlqOrigin origin = DlqHeaders.parse(recordWith(fullOriginHeaders(), "k", "{}"));

            assertThat(origin.hasUnknownConsumerGroup()).isFalse();
        }
    }

    @Test
    @DisplayName("같은 헤더가 누적되면 마지막 값을 쓴다 — appendOriginalHeaders 기본 true 라 재-DLQ 시 쌓인다")
    void usesLastHeaderValue() {
        RecordHeaders h = fullOriginHeaders();
        h.add(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP,
                "product-svc-payment-completed-group".getBytes(StandardCharsets.UTF_8));

        DlqOrigin origin = DlqHeaders.parse(recordWith(h, "k", "{}"));

        assertThat(origin.failedConsumerGroup()).isEqualTo("product-svc-payment-completed-group");
    }

    @Test
    @DisplayName("payload 는 레코드 value 를 그대로 싣는다 — 파싱 불가 메시지도 원장에 남아야 한다")
    void carriesRawPayload() {
        DlqOrigin origin = DlqHeaders.parse(record("k", "invalid-json-message"));

        assertThat(origin.payload()).isEqualTo("invalid-json-message");
    }
}
