package com.cdcplatform.model;

import java.util.List;

public record PageResult<T>(List<T> items, long total, int page, int size, int totalPages) {
    public static <T> PageResult<T> of(List<T> all, int page, int size) {
        int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(size, 100));
        int from = (int) Math.min((long) safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        return new PageResult<>(all.subList(from, to), all.size(), safePage, safeSize,
                all.isEmpty() ? 0 : (int) Math.ceil((double) all.size() / safeSize));
    }
}
