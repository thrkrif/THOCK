#!/usr/bin/env bash
# H3 설계 근거: 검색 결과 건수(hits)에 따라 LIKE 와 FULLTEXT 의 목록/카운트 쿼리 시간이 어떻게 달라지는지 잰다.
# 모델 번호 접두어(M12, M123 ...)로 결과 건수를 10배씩 줄여 가며 같은 모양의 쿼리를 잰다.
# 사용: ./h3-ladder.sh   (FULLTEXT 인덱스 ft_products_name 가 있어야 함)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
ms() { "$here/explain.sh" thock_product_db "$1" | grep '^## median_ms' | sed 's/## median_ms=\([0-9.]*\).*/\1/'; }
COLS="p1_0.id,p1_0.name,p1_0.price,p1_0.sale_price,p1_0.seller_id"
printf '%-10s %8s | %12s %12s | %12s %12s\n' keyword hits like_list like_count match_list match_count
# KEYWORDS 로 다른 키워드를 줄 수 있다. 공백은 _ 로 적는다. 예: KEYWORDS="Red_TKL Mini" ./h3-ladder.sh
for kw in ${KEYWORDS:-Keychron M12 M123 M1234 M12345 M123456 Cherry}; do
  kw=${kw//_/ }
  hits=$(docker exec thock-mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N thock_product_db -e \"SELECT COUNT(*) FROM products WHERE name LIKE '%$kw%'\" 2>/dev/null")
  ll=$(ms "select $COLS from products p1_0 where p1_0.name like '%$kw%' order by p1_0.id desc limit 0,12")
  lc=$(ms "select count(p1_0.id) from products p1_0 where p1_0.name like '%$kw%'")
  ml=$(ms "select $COLS from products p1_0 where match(p1_0.name) against('\"$kw\"' in boolean mode) order by p1_0.id desc limit 0,12")
  mc=$(ms "select count(p1_0.id) from products p1_0 where match(p1_0.name) against('\"$kw\"' in boolean mode)")
  printf '%-10s %8s | %12s %12s | %12s %12s\n' "$kw" "$hits" "$ll" "$lc" "$ml" "$mc"
done
