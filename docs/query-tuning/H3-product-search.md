# H3. 상품명 검색 `LIKE '%키워드%'`

## 한 줄 요약

분석 문서의 원래 안(FULLTEXT ngram)을 적용해 보니 **흔한 키워드(브랜드명)에서 기존보다 10~20배 느려졌다.** 측정을 근거로 FULLTEXT 를 버리고,
`(name)` 일반 인덱스 + "count 먼저 → 결과 건수에 따라 목록 쿼리 선택"으로 바꿨다.
검색 1회(목록 + count)의 DB 시간: 흔한 키워드 **211 → 109ms**, 드문 키워드 **545 → 224ms**, 결과 없음 **482 → 105ms**.

## 1. 증상

```
Count: 158  Time=0.62s (98s)    select p1_0.id, ... from products p1_0 where p1_0.name like ... order by p1_0.id desc limit ...
Count:  97  Time=0.35s (33s)    select count(p1_0.id) from products p1_0 where p1_0.name like 'S' escape '\'

# 드문 키워드 목록: Query_time: 0.625669  Rows_sent: 1  Rows_examined: 500522
# count:          Query_time: 0.416198  Rows_sent: 1  Rows_examined: 500522  Read_rnd_next: 500523
```

k6 기준 검색 API p95: 흔한 키워드 531ms, 드문 키워드 821ms, 결과 없음 822ms.

## 2. 원인 분석

```sql
EXPLAIN SELECT ... FROM products p1_0 WHERE p1_0.name LIKE '%M123456%' ORDER BY p1_0.id DESC LIMIT 0,12;
EXPLAIN SELECT count(p1_0.id) FROM products p1_0 WHERE p1_0.name LIKE '%M123456%';
```

| 쿼리 | type | key | Extra | 시간 |
|---|---|---|---|---|
| 목록 | index | PRIMARY | Using where; Backward index scan | 339ms |
| count | ALL | NULL | Using where | 205ms |

```
-> Limit: 12 row(s)  (actual time=265..344 rows=1 loops=1)
    -> Filter: (p1_0.name like '%M123456%')  (actual time=265..344 rows=1 loops=1)
        -> Index scan on p1_0 using PRIMARY (reverse)  (actual time=0.153..259 rows=500522 loops=1)
```

- `%` 로 시작하는 LIKE 는 B-tree 로 "찾아 들어갈" 수 없다
- 목록: PK 를 최신부터 거꾸로 읽다가 12건을 찾으면 멈춘다. **흔한 키워드는 금방 멈추고(0.13ms), 드문 키워드는 테이블 끝까지 읽는다(339ms)**
- count: 결과 건수와 무관하게 항상 50만 행 전체(테이블 270MB)를 읽는다

## 3. 1차 시도: FULLTEXT(ngram) — 채택하지 않음

### 3-1. 적용

```sql
SET SESSION innodb_ft_enable_stopword = OFF;   -- 이유는 3-3
ALTER TABLE products ADD FULLTEXT INDEX ft_products_name (name) WITH PARSER ngram;   -- 26초, 테이블 재구성
SELECT ... WHERE MATCH(name) AGAINST('"Keychron"' IN BOOLEAN MODE) ORDER BY id DESC LIMIT 12;
```

### 3-2. 결과 건수별 측정 (`scripts/h3-ladder.sh`)

모델 번호 접두어로 결과 건수를 10배씩 줄여 가며 쟀다(서버 실행 시간 중앙값, ms).

| 키워드 | 결과 건수 | LIKE 목록 | LIKE count | FULLTEXT 목록 | FULLTEXT count |
|---|---|---|---|---|---|
| TKL | 208,310 | 0.08 | 219 | **3,406** | **1,757** |
| Keychron | 62,522 | 0.11 | 218 | **2,367** | **1,256** |
| M12 | 11,111 | 224 | 230 | 133 | 88 |
| M123 | 1,111 | 254 | 239 | 44 | 47 |
| M123456 | 1 | 300 | 244 | 57 | 64 |
| Cherry | 0 | 305 | 237 | 26 | 25 |
| Linear Full | 0 | 337 | 250 | 355 | 319 |

- 결과가 적은 키워드는 FULLTEXT 가 4~10배 빠르다
- **흔한 키워드는 FULLTEXT 가 10~20배 느리다.** 6만 건의 일치 행을 다 모은 뒤 정렬하고, 토큰 8개의 위치를 맞춰 보는 비용이 크다
- `Linear Full` 은 결과가 0건인데도 355ms. **FULLTEXT 비용은 결과 건수가 아니라 토큰(2글자 조각)이 얼마나 흔한지에 비례**한다.
  이 상품명들은 적은 단어를 조합해 만들어서 거의 모든 토큰이 흔하다
- 키보드 쇼핑몰에서 가장 흔한 검색은 브랜드명이라, 이대로 바꾸면 가장 많이 쓰는 검색이 가장 느려진다

### 3-3. 함께 알게 된 함정 (MySQL 8.0 Reference Manual, ngram Full-Text Parser)

- **불용어**: ngram 파서는 불용어를 "포함하는" 토큰을 버린다. 기본 불용어에 `a`, `i`, `in` 이 있어서 기본 설정으로 만든 인덱스(5만 건 복제 테이블)에서
  `Mini`(토큰 mi, in, ni 모두 불용어 포함)는 LIKE 8,284건 / MATCH **0건**, `Ra` 는 6,216건 / **0건**이었다(`results/explain/H3-stopword-pitfall.txt`).
  불용어 설정은 인덱스를 만드는 순간 붙으므로 `SET SESSION innodb_ft_enable_stopword = OFF` 후 생성해야 한다
- **한 글자 단어**: `G Pro`, `Logitech G` 는 LIKE 수만 건 / MATCH 0건. 2글자 토큰으로는 `G` 를 찾을 수 없다(`H3-fulltext-equivalence.txt`)
- **시간 측정**: FULLTEXT `count(*)` 는 `EXPLAIN ANALYZE` 에서 `Select tables optimized away`, 0ms 로 보이지만 실제 서버 시간은 1,258ms 였다.
  옵티마이저 단계에서 답을 구해서 `actual time` 에 안 잡힌다 → 이후 모든 측정을 서버 시간(`events_statements_history`)으로 바꿨다

## 4. 2차: 일반 `(name)` 인덱스 + 결과 건수별 목록 쿼리 — 채택

### 4-1. 아이디어

`(name)` 인덱스는 `name` 과 PK(`id`)만 담아 **25MB**(테이블은 270MB)다.

```sql
SELECT ROUND(stat_value * @@innodb_page_size / 1024 / 1024) AS mb, index_name
FROM mysql.innodb_index_stats
WHERE database_name = 'thock_product_db' AND table_name = 'products' AND stat_name = 'size';
-- PRIMARY 271, idx_products_name 25
```

LIKE '%…%' 로 "찾아 들어갈" 수는 없지만, **끝까지 훑을 때 읽는 양이 1/10** 이다.

### 4-2. 실험

| 방식 | 흔한(Keychron) | 드문(M123456) | 없음(Cherry) | 비고 |
|---|---|---|---|---|
| count: 테이블 풀스캔 (기존) | 211ms | 205ms | 213ms | `ALL` |
| count: 이름 인덱스 (인덱스만 만들면 옵티마이저가 자동 선택) | **109ms** | **112ms** | **105ms** | `index`, `Using index` |
| 목록: PK 역순 (기존) | **0.11ms** | 339ms | 269ms | 일치 행이 촘촘해야 빨리 멈춤 |
| 목록: `FORCE INDEX(name)` | 273ms | 244ms | 230ms | 무시됨(`ALL`). 목록은 price 등 인덱스에 없는 컬럼이 필요 |
| 목록: 지연 조인 (안쪽만 이름 인덱스) | 101ms | **102ms** | 102ms | 결과 건수와 무관하게 일정 |

지연 조인(deferred join): 안쪽 쿼리는 인덱스에 다 있는 `id` 만 골라 정렬/LIMIT 하고, 바깥에서 그 12건만 PK 로 읽는다.

```sql
SELECT p.* FROM products p
JOIN (SELECT id FROM products FORCE INDEX (idx_products_name)
      WHERE name LIKE CONCAT('%', ?, '%')
      ORDER BY id DESC LIMIT 12 OFFSET 0) matched ON matched.id = p.id
ORDER BY p.id DESC;
```

```
-> Nested loop inner join  (actual time=124..124 rows=1 loops=1)
    -> Table scan on matched -> Materialize -> Limit: 12 row(s)
        -> Sort: products.id DESC, limit input to 12 row(s) per chunk  (actual time=124..124 rows=1 loops=1)
            -> Filter: (products.name like ...)  (actual time=55.3..124 rows=1 loops=1)
                -> Index scan on products using idx_products_name  (actual time=0.146..50 rows=500522 loops=1)
    -> Single-row index lookup on p using PRIMARY (id=matched.id)
```

### 4-3. 최종 설계 (`ProductQueryService.searchProductsByKeyword`)

응답 형식(`Page`)은 그대로다. 쿼리 실행 방식만 바꿨다.

1. **count 를 먼저 구한다.** `Page` 응답에 어차피 필요하다. 이름 인덱스 커버링 스캔(약 105ms)
2. **0건이면 목록 쿼리를 생략한다**
3. **2만 건 이상이면 기존 PK 역순 스캔**(`findByNameContainingOrderByIdDesc`). 일치 행이 촘촘해서 몇백 행만 읽고 멈춘다
4. **그보다 적으면 지연 조인**(`findByNameLikeUsingNameIndex`). 약 100ms 로 일정하다

기준값 2만 건: 실측에서 6.2만 건 이상은 PK 역순이 0.1ms, 1.1만 건 이하는 300~450ms 였다. 그 사이 값으로 정했다.
상품 수가 크게 늘면 다시 재서 조정해야 한다(코드 주석에도 남김).

- 정렬은 `id DESC` 고정(프론트도 `sort=id,desc` 만 보낸다)
- 네이티브 LIKE 라 사용자가 입력한 `%`, `_`, `\` 를 이스케이프한다(Spring Data `Containing` 과 같은 규칙)
- LIKE 를 그대로 쓰므로 **검색 결과는 기존과 완전히 같다**

## 5. 재측정

| 검색 1회 (count + 목록) | 전 | 후 |
|---|---|---|
| 흔한 키워드 (Keychron, 6.2만 건) | 211 + 0.13 = **211ms** | 109 + 0.11 = **109ms** |
| 드문 키워드 (M123456, 1건) | 205 + 339 = **544ms** | 112 + 111 = **223ms** |
| 결과 없음 (Cherry) | 213 + 269 = **482ms** | 105 + 생략 = **105ms** |

API p95(k6): 흔한 키워드 531 → 236ms, 드문 키워드 821 → 484ms, 결과 없음 822 → 266ms.

## 6. 남은 한계

- 여전히 매 검색마다 이름 인덱스 50만 건을 훑는다(약 100ms). 상품이 수백만 건이 되면 검색 엔진(Elasticsearch/OpenSearch) 이 필요하다
- 2만 건 이상이지만 일치 행이 오래된 상품에만 몰린 키워드는 PK 역순 스캔이 끝까지 갈 수 있다(최악의 경우 기존과 같은 수준)

## 사용한 명령

```sql
EXPLAIN ANALYZE SELECT ... WHERE p1_0.name LIKE '%M123456%' ORDER BY p1_0.id DESC LIMIT 0,12;
ALTER TABLE products ADD FULLTEXT INDEX ft_products_name (name) WITH PARSER ngram;
SELECT COUNT(*) FROM products WHERE MATCH(name) AGAINST('"Keychron"' IN BOOLEAN MODE);
SET SESSION innodb_ft_enable_stopword = OFF;
ALTER TABLE products DROP INDEX ft_products_name;  ALTER TABLE products FORCE;   -- FULLTEXT 실험 정리(숨은 FTS_DOC_ID 컬럼 제거)
CREATE INDEX idx_products_name ON products (name);
SELECT id FROM products FORCE INDEX (idx_products_name) WHERE name LIKE '%M123%' ORDER BY id DESC LIMIT 12;
```

```bash
./scripts/h3-ladder.sh                          # 결과 건수별 LIKE vs FULLTEXT
KEYWORDS="TKL Mini Red_TKL" ./scripts/h3-ladder.sh
./scripts/fulltext-equivalence.sh               # LIKE 와 MATCH 결과 건수가 같은지
```

원본 결과: `results/explain/H3-*.txt`
