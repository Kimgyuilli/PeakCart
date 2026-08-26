package com.peekcart.global.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * DLQ 레코드에서 {@link DlqOrigin} 을 뽑는다 (계획 ④-c-2a P1).
 *
 * <p><b>커스텀 헤더를 만들지 않는다.</b> 계획 초안은 "consumer group 헤더가 없으니 직접 주입해야 한다"고
 * 판단했으나 틀렸다 — {@code spring-kafka-3.3.14} 의 {@code DeadLetterPublishingRecoverer} 는
 * {@code whichHeaders = EnumSet.allOf(HeadersToAdd.class)} 가 기본값이라 {@code GROUP} 을 포함해
 * 전 헤더를 붙이며, 값은 {@code ListenerExecutionFailedException#getGroupId()} 에서 온다(§2.3).
 *
 * <p><b>입력이 {@link Headers} 가 아니라 {@link ConsumerRecord} 인 이유</b>: 원본 key 를 담는
 * {@code DLT_ORIGINAL_KEY} 헤더는 <b>존재하지 않는다</b>({@code DLT_*} 13개 중 없음.
 * {@code DLT_KEY_EXCEPTION_*} 는 key 역직렬화 예외 정보다). 원본 key 는 recoverer 가 만든
 * DLQ 레코드 자신의 key 로 보존되므로 {@code record.key()} 에서 읽어야 한다(§2.4).
 *
 * <p>헤더 인코딩은 recoverer 구현과 맞춘다 — topic·group 은 UTF-8, partition 은 4바이트 int,
 * offset·timestamp 는 8바이트 long (big-endian).
 */
public final class DlqHeaders {

    private DlqHeaders() {
    }

    /**
     * DLQ 레코드를 원장 적재 입력으로 변환한다.
     *
     * <p>origin 헤더 3종(topic·partition·offset)을 전부 판독하면 {@link DlqOriginKind#RESOLVED_ORIGIN},
     * 하나라도 없거나 깨졌으면 DLQ 레코드 자신의 좌표를 쓰는 {@link DlqOriginKind#DLQ_ORIGIN} 이 된다.
     * <b>예외를 던지지 않는다</b> — 판독 실패는 적재 대상이지 유실 사유가 아니다(§2.6-C).
     */
    public static DlqOrigin parse(ConsumerRecord<String, String> record) {
        Headers headers = record.headers();

        String originTopic = readString(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        Integer originPartition = readInt(headers, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        Long originOffset = readLong(headers, KafkaHeaders.DLT_ORIGINAL_OFFSET);

        boolean originResolved = originTopic != null && !originTopic.isBlank()
                && originPartition != null && originOffset != null;

        String group = readString(headers, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP);
        if (group == null || group.isBlank()) {
            group = DlqOrigin.UNKNOWN_CONSUMER_GROUP;
        }

        return new DlqOrigin(
                originResolved ? DlqOriginKind.RESOLVED_ORIGIN : DlqOriginKind.DLQ_ORIGIN,
                originResolved ? originTopic : record.topic(),
                originResolved ? originPartition : record.partition(),
                originResolved ? originOffset : record.offset(),
                group,
                record.key(),
                readLong(headers, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP),
                readString(headers, KafkaHeaders.DLT_EXCEPTION_FQCN),
                readString(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                record.value()
        );
    }

    private static String readString(Headers headers, String key) {
        byte[] value = lastValue(headers, key);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    /** 4바이트가 아니면 판독 실패로 본다 — 짧은 배열에 {@code getInt} 를 쓰면 예외가 난다. */
    private static Integer readInt(Headers headers, String key) {
        byte[] value = lastValue(headers, key);
        return (value == null || value.length != Integer.BYTES) ? null : ByteBuffer.wrap(value).getInt();
    }

    private static Long readLong(Headers headers, String key) {
        byte[] value = lastValue(headers, key);
        return (value == null || value.length != Long.BYTES) ? null : ByteBuffer.wrap(value).getLong();
    }

    /**
     * 같은 키가 여러 번 있으면 마지막 값을 쓴다.
     * {@code appendOriginalHeaders} 가 기본 true 라 재-DLQ 시 헤더가 누적될 수 있고,
     * 그때 유효한 것은 가장 최근 값이다.
     */
    private static byte[] lastValue(Headers headers, String key) {
        byte[] last = null;
        for (Header header : headers.headers(key)) {
            last = header.value();
        }
        return last;
    }
}
