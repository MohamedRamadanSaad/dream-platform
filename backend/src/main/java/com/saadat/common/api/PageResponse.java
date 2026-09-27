package com.saadat.common.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** JSON page envelope matching types.ts {@code Page<T> = {items, page, size, total}}. */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    public static <S, T> PageResponse<T> from(Page<S> page, Function<? super S, ? extends T> mapper) {
        List<T> items = page.getContent().stream().<T>map(mapper).toList();
        return new PageResponse<>(items, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        return new PageResponse<>(items, page, size, total);
    }
}
