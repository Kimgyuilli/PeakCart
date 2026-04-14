package com.peekcart.global.kafka;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Kafka Consumer 진입 시 MDC 에 traceId/userId/orderId 를 주입하고, record 처리 종료 시 정리한다.
 * <p>
 * traceId 우선순위:
 * <ol>
 *   <li>{@link KafkaTraceHeaders#TRACE_ID} 헤더 (Producer 측 trace context 영속화 후 — D-010)</li>
 *   <li>payload 의 {@code eventId} (재시도/DLQ 경로까지 동일 ID 로 묶임)</li>
 *   <li>신규 UUID (방어적 fallback)</li>
 * </ol>
 * userId/orderId 는 헤더 우선, 없으면 payload 에서 best-effort 추출.
 * <p>
 * 정리는 success / failure / afterRecord 3개 hook 모두에서 수행하여 누수 위험을 차단한다.
 */
@RequiredArgsConstructor
public class MdcRecordInterceptor implements RecordInterceptor<String, String> {

    static final String MDC_TRACE_ID = "traceId";
    static final String MDC_USER_ID = "userId";
    static final String MDC_ORDER_ID = "orderId";

    private final MdcPayloadExtractor payloadExtractor;

    @Override
    public ConsumerRecord<String, String> intercept(ConsumerRecord<String, String> record,
                                                    Consumer<String, String> consumer) {
        MdcPayloadExtractor.Extracted extracted = payloadExtractor.extract(record.value());

        String traceId = headerValue(record, KafkaTraceHeaders.TRACE_ID);
        if (traceId == null) {
            traceId = extracted.eventId();
        }
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(MDC_TRACE_ID, traceId);

        String userId = headerValue(record, KafkaTraceHeaders.USER_ID);
        if (userId == null) {
            userId = extracted.userId();
        }
        if (userId != null) {
            MDC.put(MDC_USER_ID, userId);
        }

        if (extracted.orderId() != null) {
            MDC.put(MDC_ORDER_ID, extracted.orderId());
        }

        return record;
    }

    @Override
    public void success(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        clearMdc();
    }

    @Override
    public void failure(ConsumerRecord<String, String> record, Exception exception,
                        Consumer<String, String> consumer) {
        clearMdc();
    }

    @Override
    public void afterRecord(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        clearMdc();
    }

    private static void clearMdc() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_USER_ID);
        MDC.remove(MDC_ORDER_ID);
    }

    private static String headerValue(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header == null || header.value() == null) {
            return null;
        }
        String value = new String(header.value(), StandardCharsets.UTF_8);
        return value.isBlank() ? null : value;
    }
}
