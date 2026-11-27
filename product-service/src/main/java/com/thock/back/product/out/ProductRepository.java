package com.thock.back.product.out;

import com.thock.back.product.domain.Category;
import com.thock.back.product.domain.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    // 상품명으로 검색 (부분 일치) - LIKE '%keyword%'
    Page<Product> findByNameContaining(String keyword, Pageable pageable);

    // ---- [H3] 상품명 검색: count 를 먼저 구하고 결과 건수에 따라 목록 쿼리를 고른다 ----
    // count: idx_products_name(name) 커버링 인덱스 스캔 (테이블 270MB 대신 인덱스 25MB 만 읽음)
    long countByNameContaining(String keyword);

    // 결과가 많은 검색어: PK(id) 역순으로 읽다가 LIMIT 만큼 찾으면 멈춘다. (count 쿼리 없음)
    List<Product> findByNameContainingOrderByIdDesc(String keyword, Pageable pageable);

    // 결과가 적은 검색어: 이름 인덱스에서 일치하는 id 만 골라 정렬한 뒤(지연 조인) 그 id 로 행을 읽는다.
    // PK 역순 스캔은 일치하는 행이 드물면 테이블 끝까지 읽어서, 결과가 적을수록 오히려 느렸다.
    // pattern 은 LIKE 특수문자(\ % _)를 이스케이프한 값이어야 한다.
    @Query(value = """
            SELECT p.* FROM products p
            JOIN (SELECT id FROM products FORCE INDEX (idx_products_name)
                  WHERE name LIKE CONCAT('%', :pattern, '%')
                  ORDER BY id DESC LIMIT :limit OFFSET :offset) matched ON matched.id = p.id
            ORDER BY p.id DESC
            """, nativeQuery = true)
    List<Product> findByNameLikeUsingNameIndex(@Param("pattern") String pattern,
                                               @Param("limit") int limit,
                                               @Param("offset") long offset);

    // 카테고리로 검색 (페이징)
    Page<Product> findByCategory(Category category, Pageable pageable);

    // 여러 상품 ID로 조회
    List<Product> findAllByIdIn(List<Long> ids);

    // 여러 상품 ID로 조회하면서 PESSIMISTIC_WRITE 락을 거는 메서드
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids")
    List<Product> findAllByIdInForUpdate(@Param("ids") List<Long> ids);

    // 판매자 ID로 상품 조회 (페이징)
    Page<Product> findBySellerId(Long sellerId, Pageable pageable);
}
