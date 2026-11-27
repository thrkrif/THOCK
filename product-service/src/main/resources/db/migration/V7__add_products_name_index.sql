-- H3: 상품명 검색 (name LIKE '%키워드%')
-- 앞에 %가 붙어 B-tree 로 "찾아 들어갈" 수는 없지만, 이 인덱스는 (name, id)만 담아 테이블(약 270MB)보다
-- 훨씬 작다(약 25MB). 그래서
--  * 검색 count(*) 는 옵티마이저가 테이블 대신 이 인덱스를 끝까지 훑는다(커버링 인덱스 스캔).
--  * 결과가 적은 검색어의 목록은 이 인덱스에서 일치하는 id 만 찾고 PK 로 12건만 읽는다(지연 조인).
-- FULLTEXT(ngram)도 실험했지만 흔한 키워드(브랜드명)에서 기존보다 10배 이상 느려져 쓰지 않는다.
-- (docs/query-tuning/H3-product-search.md)
CREATE INDEX idx_products_name ON products (name);
