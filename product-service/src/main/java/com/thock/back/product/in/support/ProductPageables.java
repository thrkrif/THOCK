package com.thock.back.product.in.support;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Set;

/**
 * [M1] 상품 목록/검색/내 상품 API 의 정렬 화이트리스트.
 *
 * sort 파라미터가 사용자 입력이라 sort=price 처럼 인덱스가 없는 컬럼으로 정렬하면
 * 상품 전체(약 50만 행)를 읽어 filesort 한 뒤 LIMIT 을 적용한다(요청당 0.8초 안팎).
 * 인덱스로 정렬할 수 있는 id 만 허용하고, 그 밖의 정렬은 무시하고 기본 정렬(id DESC)을 쓴다.
 * 정렬 옵션을 늘리려면 해당 정렬용 인덱스(예: (category, price, id))를 먼저 만들고 여기에 추가한다.
 */
public final class ProductPageables {

    private static final Set<String> SORTABLE_PROPERTIES = Set.of("id");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "id");

    private ProductPageables() {
    }

    public static Pageable withAllowedSort(Pageable pageable) {
        List<Sort.Order> allowed = pageable.getSort().stream()
                .filter(order -> SORTABLE_PROPERTIES.contains(order.getProperty()))
                .toList();
        Sort sort = allowed.isEmpty() ? DEFAULT_SORT : Sort.by(allowed);
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
