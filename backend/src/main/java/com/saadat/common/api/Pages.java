package com.saadat.common.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Builds a safe {@link Pageable} from the frontend's {@code page}/{@code size} query params. */
public final class Pages {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private Pages() {
    }

    public static Pageable of(Integer page, Integer size) {
        return of(page, size, Sort.unsorted());
    }

    public static Pageable of(Integer page, Integer size, Sort sort) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return PageRequest.of(p, s, sort);
    }
}
