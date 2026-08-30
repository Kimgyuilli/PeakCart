package com.peekcart.order.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.domain.exception.OrderException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Base64;

/**
 * 주문 목록 커서. {@code (orderedAt, id)} 복합 정렬 키의 한 위치를 가리킨다.
 *
 * <p><b>권한 토큰이 아니다</b> — 서명하지 않으며 {@code userId} 를 담지 않는다. 조회 대상은 항상
 * 인증 주체에서 오고 커서는 위치 조건에만 쓰인다. 따라서 유효한 형식의 위치 조작은 허용된 동작이다.
 *
 * <p>클라이언트에는 base64url 문자열로만 노출한다 — 내부 정렬 키를 투명하게 드러내면 그것이 계약이 된다.
 */
public record OrderCursor(LocalDateTime orderedAt, Long id) {

    private static final String DELIMITER = "|";

    /**
     * 고정 6자리 마이크로초. {@code ordered_at} 이 {@code DATETIME(6)} 이라 표현 범위를 맞춘다.
     *
     * <p>{@code ISO_LOCAL_DATE_TIME} 은 쓰지 않는다 — 소수부 자릿수에 따라 출력이 가변이라
     * ({@code T03:04:00} / {@code .000001} / {@code .123456789}) 같은 시각이 다르게 직렬화된다.
     *
     * <p>{@code ResolverStyle.STRICT} 가 필수다. 기본값 {@code SMART} 는 존재하지 않는 날짜를
     * 거부하지 않고 보정한다 ({@code 2026-02-30} → {@code 2026-02-28}) — 커서가 가리키는 위치가
     * 조용히 바뀐다.
     */
    private static final DateTimeFormatter FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS")
            .withResolverStyle(ResolverStyle.STRICT);

    /**
     * MySQL {@code DATETIME(6)} 저장 가능 범위. 이 밖의 값은 decode 를 통과시키면 JDBC/DB 단계에서
     * 500 이 되므로 여기서 {@code ORD-010} 으로 거부한다.
     *
     * <p>상한은 {@code .999999} 다 — MySQL 8.0.46 에 세 경계값을 직접 저장해 확인했다.
     * 문서의 {@code .499999} 는 컬럼보다 많은 소수 자릿수를 넣어 반올림할 때의 경계이지
     * {@code DATETIME(6)} 의 저장 상한이 아니다.
     *
     * <p>하한/상한 검사가 연도 검사를 겸한다 — 패턴 {@code uuuu} 는 STRICT 로도 부호형
     * {@code +10000} / {@code -0001} 을 파싱 통과시킨다.
     */
    private static final LocalDateTime MIN_ORDERED_AT = LocalDateTime.of(1, 1, 1, 0, 0, 0, 0);
    private static final LocalDateTime MAX_ORDERED_AT = LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000);

    public String encode() {
        String raw = FORMAT.format(orderedAt) + DELIMITER + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws OrderException 커서가 구조적으로 해석 불가하거나 저장 범위를 벗어나면 {@code ORD-010}
     */
    public static OrderCursor decode(String encoded) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new OrderException(ErrorCode.ORD_010);
        }

        // -1: 구분자가 2개 이상이면 조각이 3개가 되어 아래 길이 검사에 걸린다.
        String[] parts = raw.split("\\" + DELIMITER, -1);
        if (parts.length != 2) {
            throw new OrderException(ErrorCode.ORD_010);
        }

        LocalDateTime orderedAt;
        long id;
        try {
            orderedAt = LocalDateTime.parse(parts[0], FORMAT);
            id = Long.parseLong(parts[1]);
        } catch (RuntimeException e) {
            throw new OrderException(ErrorCode.ORD_010);
        }

        if (id <= 0) {
            throw new OrderException(ErrorCode.ORD_010);
        }
        if (orderedAt.isBefore(MIN_ORDERED_AT) || orderedAt.isAfter(MAX_ORDERED_AT)) {
            throw new OrderException(ErrorCode.ORD_010);
        }
        // DATETIME(6) 으로 표현 불가한 나노 잔여는 별도로 검사하지 않는다 — 패턴의 SSSSSS 가
        // 정확히 6자리만 받으므로 파싱을 통과한 값의 나노는 항상 1000 의 배수다.
        return new OrderCursor(orderedAt, id);
    }
}
