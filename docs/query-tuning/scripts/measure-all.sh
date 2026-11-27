#!/usr/bin/env bash
# 튜닝 대상 쿼리를 한 번에 측정한다. (각 쿼리: EXPLAIN + EXPLAIN ANALYZE + 5회 중앙값)
# SQL 은 baseline 슬로우 로그에 찍힌 Hibernate SQL 과 같은 모양으로, 리터럴 값만 고정했다.
#
# 사용: ./measure-all.sh <before|after> [쿼리 id ...]
#   결과: docs/query-tuning/results/explain/<id>-<phase>.txt, 요약: .../explain/summary-<phase>.txt
#   before: 인덱스/코드 변경 전 SQL,  after: 변경 후 SQL (코드가 바뀐 쿼리는 SQL 도 다름)
set -euo pipefail
phase=${1:?before|after}; shift || true
here=$(cd "$(dirname "$0")" && pwd)
out="$here/../results/explain"; mkdir -p "$out"

M=thock_market_db; P=thock_product_db; PAY=thock_payment_db; S=thock_settlement_db
NEWEST12="624839,624840,624841,624842,624843,624844,624845,624846,624847,624848,624849,624850"
ORDER_COLS="o1_0.id,o1_0.buyer_id,o1_0.cancel_date,o1_0.created_at,o1_0.order_number,o1_0.state,o1_0.request_payment_date,o1_0.total_price"

# id | db | before SQL | after SQL (비우면 before 와 같음)
QUERIES=(
"H1-1|$M|select count(o1_0.id) from market_orders o1_0 where o1_0.state='CONFIRMED'|"
"H1-2|$M|select o1_0.state,count(o1_0.id) from market_orders o1_0 group by o1_0.state|"
"H1-3|$M|select $ORDER_COLS from market_orders o1_0 where o1_0.state='PENDING_PAYMENT' and o1_0.request_payment_date<now()-interval 30 minute|"
"H1-4|$M|select $ORDER_COLS from market_orders o1_0 where o1_0.state='CANCELLED' and o1_0.cancel_date<now()-interval 30 day|"
"H1-5|$M|select $ORDER_COLS from market_orders o1_0 where o1_0.state='CANCELLED' and o1_0.cancel_date<now()-interval 364 day|"
"H2-1|$PAY|select p1_0.id,p1_0.amount,p1_0.buyer_id,p1_0.order_id,p1_0.pg_amount,p1_0.status from payment_payments p1_0 where p1_0.order_id='PERF-500000'|"
# H3: 검색 = count + 목록. 변경 후 목록은 결과 건수에 따라 다름 (많음: PK 역순 그대로 / 적음: 이름 인덱스 지연 조인 / 0건: 목록 생략)
"H3-1|$P|select p1_0.id,p1_0.name,p1_0.price,p1_0.sale_price,p1_0.seller_id from products p1_0 where p1_0.name like '%Keychron%' order by p1_0.id desc limit 0,12|"
"H3-2|$P|select count(p1_0.id) from products p1_0 where p1_0.name like '%Keychron%'|"
"H3-3|$P|select p1_0.id,p1_0.name,p1_0.price,p1_0.sale_price,p1_0.seller_id from products p1_0 where p1_0.name like '%M123456%' order by p1_0.id desc limit 0,12|select p.* from products p join (select id from products force index (idx_products_name) where name like concat('%','M123456','%') order by id desc limit 12 offset 0) matched on matched.id = p.id order by p.id desc"
"H3-4|$P|select count(p1_0.id) from products p1_0 where p1_0.name like '%M123456%'|"
"H3-5|$P|select p1_0.id,p1_0.name,p1_0.price,p1_0.sale_price,p1_0.seller_id from products p1_0 where p1_0.name like '%Cherry%' order by p1_0.id desc limit 0,12|select 'list skipped: count=0'"
"H3-6|$P|select count(p1_0.id) from products p1_0 where p1_0.name like '%Cherry%'|"
"H4-1|$P|select p1_0.id,p1_0.name,p1_0.price,p1_0.seller_id from products p1_0 where p1_0.seller_id=100 order by p1_0.id desc limit 0,10|"
"H4-2|$P|select count(p1_0.id) from products p1_0 where p1_0.seller_id=100|"
"H4-3|$P|select count(p1_0.id) from products p1_0 where p1_0.seller_id=4|"
"M1-1|$P|select p1_0.id,p1_0.name,p1_0.price from products p1_0 where p1_0.category='KEYBOARD' order by p1_0.price limit 24,12|"
"M1-2|$P|select p1_0.id,p1_0.name,p1_0.price,p1_0.description from products p1_0 where p1_0.category='KEYBOARD' order by p1_0.id desc limit 0,2000|select p1_0.id,p1_0.name,p1_0.price,p1_0.description from products p1_0 where p1_0.category='KEYBOARD' order by p1_0.id desc limit 0,100"
"M1-3|$P|select count(p1_0.id) from products p1_0 where p1_0.category='KEYBOARD'|"
"M2-1|$M|select distinct $ORDER_COLS,i1_0.id,i1_0.product_name,i1_0.state from market_orders o1_0 left join market_order_items i1_0 on o1_0.id=i1_0.order_id where o1_0.buyer_id=100 order by o1_0.created_at desc|"
"M2-2|$M|select distinct $ORDER_COLS,i1_0.id,i1_0.product_name,i1_0.state from market_orders o1_0 left join market_order_items i1_0 on o1_0.id=i1_0.order_id where o1_0.buyer_id=4 order by o1_0.created_at desc|"
"M3-1|$M|select oi1_0.id,oi1_0.order_id,oi1_0.state,oi1_0.delivered_at from market_order_items oi1_0 where oi1_0.state='DELIVERED' and oi1_0.delivered_at<now()-interval 7 day|"
"M4-1|$M|select s1_0.id,s1_0.order_item_id,s1_0.seller_id,s1_0.status,s1_0.created_at from market_shipments s1_0 where s1_0.seller_id=100 order by s1_0.created_at desc|select s1_0.id,s1_0.seller_id,s1_0.status,oi1_0.id,oi1_0.product_name,o1_0.id,o1_0.order_number from market_shipments s1_0 join market_order_items oi1_0 on oi1_0.id=s1_0.order_item_id join market_orders o1_0 on o1_0.id=oi1_0.order_id where s1_0.seller_id=100 order by s1_0.created_at desc"
"M5-1|$M|select count(oe1_0.id) from outbox_event oe1_0 where oe1_0.status='SENT'|select oe1_0.status,count(oe1_0.id) from outbox_event oe1_0 group by oe1_0.status"
"M5-2|$M|select count(*) from outbox_event oe1_0 where oe1_0.status='SENT' and oe1_0.sent_at<now()-interval 7 day|select count(*) from outbox_event oe1_0 where oe1_0.status='SENT' and oe1_0.created_at<now()-interval 7 day and oe1_0.sent_at<now()-interval 7 day"
"M5-3|$M|select oe1_0.id,oe1_0.status,oe1_0.retry_count from outbox_event oe1_0 where oe1_0.status='FAILED' and oe1_0.retry_count<5 order by oe1_0.created_at|"
"M6-1|$S|select sl1_0.id,sl1_0.order_no,sl1_0.seller_id,sl1_0.payment_amount from sales_log sl1_0 where sl1_0.reconciliation_status='MATCH' and sl1_0.confirmed_at is not null and sl1_0.daily_settlement_id is null|"
"M6-2|$S|select sl1_0.id,sl1_0.order_no from sales_log sl1_0 where sl1_0.snapshot_at between '2026-10-15 00:00:00' and '2026-10-15 23:59:59.999999' and sl1_0.reconciliation_status='PENDING'|"
"M7-1|$S|select sl1_0.id,sl1_0.order_no,sl1_0.transaction_type from sales_log sl1_0 where sl1_0.order_no='PERF-12345' and sl1_0.transaction_type='PAYMENT'|"
"M8-1|$P|select pr1_0.product_id,avg(pr1_0.rating),count(pr1_0.id) from product_reviews pr1_0 where pr1_0.product_id in ($NEWEST12) group by pr1_0.product_id|"
)

summary="$out/summary-$phase.txt"; : > "$summary"
for entry in "${QUERIES[@]}"; do
  IFS='|' read -r id db before after <<< "$entry"
  if [ $# -gt 0 ] && [[ ! " $* " == *" $id "* ]]; then continue; fi
  sql=$before; [ "$phase" = after ] && [ -n "$after" ] && sql=$after
  file="$out/$id-$phase.txt"
  { echo "# $id ($phase) db=$db"; echo "$sql"; echo; "$here/explain.sh" "$db" "$sql"; } > "$file"
  line=$(grep '^## median_ms' "$file" | sed 's/  (server time.*//')
  plan=$(sed -n '/^## EXPLAIN$/,/^## EXPLAIN ANALYZE/p' "$file" | grep '^| *1 ' | head -1 | awk -F'|' '{gsub(/ /,"",$6);gsub(/ /,"",$8);gsub(/ /,"",$11);gsub(/^ +| +$/,"",$13); print "type="$6" key="$8" rows="$11" Extra="$13}')
  printf '%-5s %-22s %s\n' "$id" "$line" "$plan" | tee -a "$summary"
done
