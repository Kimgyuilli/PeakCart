package com.peekcart.order.presentation.dto.request;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.OrderCursor;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

/**
 * 주문 목록 조회의 요청 파라미터. 형식 검증을 전부 여기서 끝내고 애플리케이션에는
 * 검증된 타입만 넘긴다.
 *
 * <p>파싱이 프레젠테이션에 있는 이유는 두 가지다. 형식 검증은 비즈니스 로직이 아니고,
 * 서비스에 두면 슬라이스 테스트가 검증 자체를 확인할 수 없다(서비스가 목킹되므로).
 *
 * <p>{@code @RequestParam} 대신 {@link HttpServletRequest} 를 직접 읽는다 — 선언하지 않은
 * 파라미터의 <b>존재</b>는 {@code @RequestParam} 으로 알 수 없고, {@code int} 바인딩은
 * 비수치 입력에서 400 이 아니라 500 이 된다(핸들러 부재).
 */
public record OrderPageQuery(OrderCursor cursor, int size) {

    private static final String CURSOR = "cursor";
    private static final String SIZE = "size";
    private static final int DEFAULT_SIZE = 20;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    /**
     * offset 페이징에서 넘어온 폐기 파라미터. denylist 인 이유는 캐시버스터({@code _})나
     * 추적 파라미터({@code utm_*}) 같은 무해한 입력에 400 을 주지 않기 위해서다.
     */
    private static final List<String> REMOVED_PARAMS = List.of("page", "sort", "offset");

    /**
     * 검사 순서가 곧 오류 우선순위다: 폐기 파라미터 → 커서 → size.
     *
     * @throws OrderException 폐기 파라미터 {@code ORD-012} · 커서 {@code ORD-010} · size {@code ORD-011}
     */
    public static OrderPageQuery of(HttpServletRequest request) {
        Map<String, String[]> params = request.getParameterMap();

        List<String> removed = REMOVED_PARAMS.stream().filter(params::containsKey).toList();
        if (!removed.isEmpty()) {
            throw new OrderException(ErrorCode.ORD_012,
                    "지원하지 않는 파라미터입니다: " + String.join(", ", removed)
                            + ". 커서 페이지네이션(cursor, size)을 사용하세요.");
        }

        return new OrderPageQuery(parseCursor(params.get(CURSOR)), parseSize(params.get(SIZE)));
    }

    private static OrderCursor parseCursor(String[] values) {
        if (values == null) {
            return null;
        }
        // 중복 전달은 거부한다. 첫 값만 쓰면 ?cursor=정상&cursor=쓰레기 가 검증을 통과한다.
        requireSingle(values, ErrorCode.ORD_010);
        return OrderCursor.decode(values[0]);
    }

    private static int parseSize(String[] values) {
        if (values == null) {
            return DEFAULT_SIZE;
        }
        requireSingle(values, ErrorCode.ORD_011);

        // 빈 문자열은 기본값이 아니라 오류다. @RequestParam(defaultValue=...) 였다면 빈 값이
        // 조용히 기본값으로 치환돼 잘못된 요청이 200 이 된다.
        int size;
        try {
            size = Integer.parseInt(values[0]);
        } catch (NumberFormatException e) {
            throw new OrderException(ErrorCode.ORD_011);
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new OrderException(ErrorCode.ORD_011);
        }
        return size;
    }

    private static void requireSingle(String[] values, ErrorCode errorCode) {
        if (values.length != 1) {
            throw new OrderException(errorCode);
        }
    }
}
