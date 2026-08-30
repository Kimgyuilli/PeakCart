package com.peekcart.order.presentation.dto.response;

import java.util.List;

/**
 * 커서 페이지네이션 응답. {@code totalElements}/{@code totalPages} 를 제공하지 않는다 —
 * 총 건수는 별도 {@code COUNT} 를 요구하고, 그것이 커서 전환으로 없애려는 비용이다.
 *
 * @param nextCursor 불투명 문자열. 형식에 의존하지 말 것. {@code hasNext == false} 면 {@code null}
 */
public record CursorPageResponse<T>(List<T> content, String nextCursor, boolean hasNext) {
}
