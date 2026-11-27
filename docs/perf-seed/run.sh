#!/usr/bin/env bash
# 사용: ./run.sh <seq|products|orders|items|sales|payments|shipments|outbox|reviews|all|all2> [lo hi]   (lo/hi 생략 시 전체 범위)
#   all  : 1차 적재(주문/상품/정산)   all2 : 2차 적재(결제/배송/Outbox/리뷰, 1차 적재 후 실행)
# thock-mysql 컨테이너 내부의 MYSQL_ROOT_PASSWORD 를 사용하므로 .env 를 읽지 않는다. 한 번만 실행할 것(중복 적재됨).
set -euo pipefail
cd "$(dirname "$0")"
SELF="$(pwd)/$(basename "$0")"   # all/all2 에서 자기 자신을 다시 부를 때 쓰는 절대 경로
CHUNK=${CHUNK:-100000}
PRODUCTS=${PRODUCTS:-500000}; ORDERS=${ORDERS:-1000000}; SALES=${SALES:-1000000}
OUTBOX=${OUTBOX:-500000}; REVIEWS=${REVIEWS:-1000000}; ITEM_MAX=${ITEM_MAX:-1331758}

mysql_exec() { docker exec -i thock-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" 2>/tmp/mysql.err; rc=$?; grep -v "Using a password" /tmp/mysql.err >&2; exit $rc'; }
pmax() { docker exec thock-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SELECT MAX(id) FROM thock_product_db.products" 2>/dev/null'; }

load() { # file lo hi
  local file=$1 lo=$2 hi=$3 s=$SECONDS
  { echo "SET SESSION sql_log_bin=0; SET SESSION foreign_key_checks=0;"
    echo "SET @lo=$lo; SET @hi=$hi; SET @pmax=$(pmax);"
    cat "$file"; } | mysql_exec
  echo "  $file [$lo..$hi] $((SECONDS-s))s"
}
chunked() { # file total [lo hi]
  local file=$1 total=$2 from=${3:-1} to=${4:-$2}
  for ((lo=from; lo<=to; lo+=CHUNK)); do
    hi=$((lo+CHUNK-1)); ((hi>to)) && hi=$to
    load "$file" "$lo" "$hi"
  done
}
what=${1:-all}; lo=${2:-}; hi=${3:-}
case $what in
  seq)      mysql_exec < 00_seq.sql ;;
  products) chunked 10_products.sql "$PRODUCTS" ${lo:+$lo $hi} ;;
  orders)   chunked 20_orders.sql "$ORDERS" ${lo:+$lo $hi} ;;
  items)    chunked 30_order_items.sql "$ORDERS" ${lo:+$lo $hi} ;;
  sales)    chunked 40_sales_log.sql "$SALES" ${lo:+$lo $hi} ;;
  payments)  chunked 50_payments.sql "$ORDERS" ${lo:+$lo $hi} ;;
  shipments) chunked 60_shipments.sql "$ITEM_MAX" ${lo:+$lo $hi} ;;
  outbox)    chunked 70_outbox.sql "$OUTBOX" ${lo:+$lo $hi} ;;
  reviews)   chunked 80_reviews.sql "$REVIEWS" ${lo:+$lo $hi} ;;
  all) "$SELF" seq; "$SELF" products; "$SELF" orders; "$SELF" items; "$SELF" sales ;;
  all2) "$SELF" seq; "$SELF" payments; "$SELF" shipments; "$SELF" outbox; "$SELF" reviews ;;
esac
