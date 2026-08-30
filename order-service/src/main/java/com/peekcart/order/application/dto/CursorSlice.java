package com.peekcart.order.application.dto;

import java.util.List;

/**
 * 커서 페이지네이션 결과. 총 건수를 담지 않는다 — 커서 방식은 {@code COUNT} 를 치지 않는다.
 *
 * @param nextCursor 다음 페이지 조회에 쓸 커서. {@code hasNext == false} 면 {@code null}
 */
public record CursorSlice<T>(List<T> content, String nextCursor, boolean hasNext) {
}
