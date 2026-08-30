package com.peekcart.order.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.domain.exception.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class OrderCursorTest {

    private static String cursorOf(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("encode/decode round-trip: 마이크로초까지 보존한다")
    void roundTrip_preservesMicros() {
        OrderCursor original = new OrderCursor(LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_000), 42L);

        assertThat(OrderCursor.decode(original.encode())).isEqualTo(original);
    }

    @Test
    @DisplayName("커서는 내부 정렬 키를 평문으로 노출하지 않는다")
    void encoded_isOpaque() {
        String encoded = new OrderCursor(LocalDateTime.of(2026, 1, 2, 3, 4, 5, 0), 42L).encode();

        assertThat(encoded).doesNotContain("2026").doesNotContain("|").doesNotContain("42");
    }

    @Nested
    @DisplayName("decode 거부")
    class Decode {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "!!!not-base64!!!",                                     // 비-base64url
                "MjAyNi0wMS0wMlQwMzowNDowNS4xMjM0NTYrNDI",             // 구분자 없음(+ 는 url 알파벳 아님)
        })
        @DisplayName("구조적으로 해석 불가한 입력")
        void malformedEncoding(String encoded) {
            assertOrd010(() -> OrderCursor.decode(encoded));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "abc|1",                                    // 시각이 포맷 불일치
                "2026-01-02T03:04:05.123456",               // 구분자 부재
                "2026-01-02T03:04:05.123456|1|2",           // 구분자 2개
                "2026-01-02T03:04:05.123456|-1",            // id 음수
                "2026-01-02T03:04:05.123456|0",             // id 0
                "2026-01-02T03:04:05.123456|abc",           // id 비수치
                "2026-01-02T03:04:05.123456|99999999999999999999", // id 오버플로
                "2026-01-02T03:04:05.1234567|1",            // 나노 7자리 — 포맷이 거부
                "2026-01-02T03:04:05|1",                    // 소수부 없음
                "2026-02-30T03:04:05.123456|1",             // 존재하지 않는 날짜
                "+10000-01-02T03:04:05.123456|1",           // 연도 상한 초과(부호형)
                "-0001-01-02T03:04:05.123456|1",            // 음수 연도(부호형)
        })
        @DisplayName("포맷·범위 위반")
        void invalidContent(String raw) {
            assertOrd010(() -> OrderCursor.decode(cursorOf(raw)));
        }

        /**
         * 상한 근처 세 값은 전부 DATETIME(6) 에 저장 가능하다 — 거부하면 정상 데이터를 400 으로 막는다.
         * 저장까지 되는지는 OrderCursorBoundaryIntegrationTest 가 실제 MySQL 로 확인한다.
         */
        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "9999-12-31T23:59:59.499999|1",
                "9999-12-31T23:59:59.500000|1",
                "9999-12-31T23:59:59.999999|1",
                "0001-01-01T00:00:00.000000|1",
        })
        @DisplayName("양성 대조군 — 저장 가능한 경계값은 통과한다")
        void storableBoundaries_accepted(String raw) {
            assertThat(OrderCursor.decode(cursorOf(raw))).isNotNull();
        }

        private void assertOrd010(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
            OrderException e = catchThrowableOfType(OrderException.class, callable);
            assertThat(e).isNotNull();
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORD_010);
        }
    }

    @Test
    @DisplayName("인코딩은 JVM 기본 timezone 에 의존하지 않는다")
    void encoding_isTimezoneIndependent() {
        LocalDateTime orderedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_000);
        OrderCursor cursor = new OrderCursor(orderedAt, 42L);
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            String utc = cursor.encode();

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
            String seoul = cursor.encode();

            assertThat(utc).isEqualTo(seoul);
            // 교차 복원: 다른 zone 에서 만든 커서를 읽어도 같은 값이다.
            assertThat(OrderCursor.decode(utc)).isEqualTo(OrderCursor.decode(seoul)).isEqualTo(cursor);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("null 커서는 decode 대상이 아니다")
    void nullEncoded_rejected() {
        assertThatThrownBy(() -> OrderCursor.decode(null)).isInstanceOf(RuntimeException.class);
    }
}
