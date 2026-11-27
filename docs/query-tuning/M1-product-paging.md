# M1. 상품 목록 페이징/정렬 — 페이지 크기 상한과 정렬 화이트리스트

## 한 줄 요약

`page`, `size`, `sort` 가 사용자 입력 그대로 DB 까지 가서 `sort=price` 한 번에 상품 50만 행을 정렬했다.
정렬은 인덱스로 처리할 수 있는 `id` 만 허용하고 페이지 크기 상한을 100으로 낮췄다. 가격 정렬 요청 p95 **1,153ms → 169ms**.

## 1. 증상

```
# Query_time: 0.615164  Rows_sent: 12  Rows_examined: 500558  Read_rnd_next: 500523  Sort_rows: 36
select p1_0.id, ... from products p1_0 where p1_0.category='KEYBOARD' order by p1_0.price limit 24,12;
```

12건을 보여 주려고 50만 행을 읽어 정렬했다.

## 2. 원인 분석

```sql
EXPLAIN SELECT ... FROM products p1_0 WHERE p1_0.category = 'KEYBOARD' ORDER BY p1_0.price LIMIT 24, 12;
```

| type | key | rows | Extra |
|---|---|---|---|
| ALL | NULL | 485,463 | Using where; **Using filesort** |

- `price` 인덱스가 없어 정렬을 인덱스 순서로 대신할 수 없다 → 전체를 읽고 따로 정렬(filesort)
- `category` 는 값이 `KEYBOARD` 하나뿐이라 `(category, id)` 인덱스도 사실상 테이블 전체
- `sort` 는 Spring 이 받은 그대로 `ORDER BY` 로 바꾼다. `max-page-size` 설정이 없어 `size` 상한은 Spring 기본값 2000

## 3. 해결

### 3-1. 정렬 화이트리스트 (`product/in/support/ProductPageables`)

```java
private static final Set<String> SORTABLE_PROPERTIES = Set.of("id");
private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "id");

public static Pageable withAllowedSort(Pageable pageable) {
    List<Sort.Order> allowed = pageable.getSort().stream()
            .filter(order -> SORTABLE_PROPERTIES.contains(order.getProperty()))
            .toList();
    Sort sort = allowed.isEmpty() ? DEFAULT_SORT : Sort.by(allowed);
    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
}
```

`ProductController` 의 목록(`GET /products`), 검색(`/search`), 내 상품(`/me`)에 적용했다.

- 허용하지 않은 정렬은 오류 대신 기본 정렬로 처리한다. 프론트(`productApi.ts`)는 항상 `sort=id,desc` 만 보내서 영향이 없다
- 가격순 정렬이 기능으로 필요해지면 `(category, price, id)` 인덱스를 먼저 만들고 여기에 추가한다

### 3-2. 페이지 크기 상한 (`product-service/application.yml`)

```yaml
spring:
  data:
    web:
      pageable:
        max-page-size: 100   # 넘는 값은 100으로 잘린다
```

## 4. 재측정

| 요청 | 전 (k6 p95) | 후 (k6 p95) | 비고 |
|---|---|---|---|
| `sort=price,asc` | 1,153ms | 169ms | 이제 `id DESC` 로 처리됨 |
| `size=2000` | 225ms | 136ms | 100건으로 잘림. DB 시간 2.8ms → 0.25ms, 나머지는 응답 직렬화 |

## 5. 남은 비용 — 카테고리 목록 count

after 슬로우 로그 총 시간 1위는 `select count(p1_0.id) from products p1_0 where p1_0.category='KEYBOARD'` (실행당 75~140ms) 다.
`Page` 응답이라 목록 요청마다 50만 건을 센다. 카테고리 값이 하나뿐이라 인덱스로 줄일 수 없다.
해결하려면 count 캐시나 `Slice`(다음 페이지 유무만) 가 필요한데, `Slice` 는 응답 형식(`totalElements`, `totalPages`)이 바뀌어 이번에는 하지 않았다.

## 사용한 명령

```sql
EXPLAIN SELECT ... FROM products p1_0 WHERE p1_0.category = 'KEYBOARD' ORDER BY p1_0.price LIMIT 24, 12;
EXPLAIN SELECT ... FROM products p1_0 WHERE p1_0.category = 'KEYBOARD' ORDER BY p1_0.id DESC LIMIT 0, 2000;
```
