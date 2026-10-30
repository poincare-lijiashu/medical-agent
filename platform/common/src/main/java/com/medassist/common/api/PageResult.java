package com.medassist.common.api;

import java.util.List;

/**
 * 统一分页结果。
 *
 * <p>与 {@link ApiResponse} 组合使用：{@code ApiResponse<PageResult<T>>}。
 * 分页参数由调用方校验（页码从 1 开始，页大小上限 200——防止大页拖垮数据库）。
 *
 * @param page     当前页码（1 起）
 * @param size     页大小
 * @param total    总记录数
 * @param items    当前页数据
 */
public record PageResult<T>(
        int page,
        int size,
        long total,
        List<T> items
) {

    public static final int MAX_PAGE_SIZE = 200;

    public PageResult {
        if (page < 1) {
            page = 1;
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            size = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        }
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> PageResult<T> of(int page, int size, long total, List<T> items) {
        return new PageResult<>(page, size, total, items);
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(page, size, 0L, List.of());
    }
}
