package com.peekcart.global.cache;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

public record CachedPage<T>(
        List<T> content,
        long totalElements,
        int pageNumber,
        int pageSize
) {
    public static <T> CachedPage<T> of(Page<T> page) {
        return new CachedPage<>(
                page.getContent(),
                page.getTotalElements(),
                page.getNumber(),
                page.getPageable().getPageSize());
    }

    public Page<T> toPage() {
        return new PageImpl<>(content, PageRequest.of(pageNumber, pageSize), totalElements);
    }
}
