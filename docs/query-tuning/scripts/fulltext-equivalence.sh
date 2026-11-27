#!/usr/bin/env bash
# H3 검증: 같은 키워드로 LIKE '%kw%' 와 MATCH(name) AGAINST('"kw"' IN BOOLEAN MODE) 의 결과 건수가 같은지 비교한다.
# 사용: ./fulltext-equivalence.sh [키워드 ...]
set -euo pipefail
kws=("$@"); [ ${#kws[@]} -eq 0 ] && kws=(Keychron Razer Varmilo "Royal Kludge" "G Pro" Q1 TKL "75%" M123456 M5 Cherry "Red TKL" Mini Silent Ducky ro Akko "Logitech G")
q() { docker exec -i thock-mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N thock_product_db 2>&1 | grep -v 'Using a password' || true"; }
printf '%-14s %10s %10s  %s\n' keyword like_cnt match_cnt same
for kw in "${kws[@]}"; do
  l=$(echo "SELECT COUNT(*) FROM products WHERE name LIKE CONCAT('%', '$kw', '%');" | q)
  m=$(echo "SELECT COUNT(*) FROM products WHERE MATCH(name) AGAINST('\"$kw\"' IN BOOLEAN MODE);" | q)
  printf '%-14s %10s %10s  %s\n' "$kw" "$l" "$m" "$([ "$l" = "$m" ] && echo O || echo X)"
done
