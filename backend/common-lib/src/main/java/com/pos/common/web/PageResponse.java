package com.pos.common.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * The single shape every paginated endpoint returns, so clients learn it once.
 *
 * <p>Deliberately not Spring Data's {@code Page}: that type serialises its internals, which ties
 * the wire format to a library version and leaks sort and pageable structure clients should not
 * depend on.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    /** Maps entities to DTOs without materialising an intermediate page. */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    public static <T> PageResponse<T> empty() {
        return new PageResponse<>(List.of(), 0, 0, 0L, 0, true, true);
    }
}
