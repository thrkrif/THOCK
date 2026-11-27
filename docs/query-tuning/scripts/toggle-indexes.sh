#!/usr/bin/env bash
# 이번 작업에서 추가한 B-tree 인덱스를 INVISIBLE/VISIBLE 로 바꿔 "인덱스 전/후"를 같은 데이터로 비교한다.
# INVISIBLE 인덱스는 옵티마이저가 무시하지만 데이터는 계속 유지된다(삭제/재생성 비용 없음, MySQL 8.0+).
#
# 사용: ./toggle-indexes.sh before   # 새 인덱스 INVISIBLE + (M6) 기존 단일 상태 인덱스 복원
#       ./toggle-indexes.sh after    # 새 인덱스 VISIBLE + (M6) 기존 단일 상태 인덱스 삭제
#
set -euo pipefail
mode=${1:?before|after}
vis=$([ "$mode" = before ] && echo INVISIBLE || echo VISIBLE)

INDEXES=(
  "thock_market_db.market_orders idx_orders_state_request_payment"
  "thock_market_db.market_orders idx_orders_state_cancel_date"
  "thock_market_db.market_orders idx_orders_buyer_created"
  "thock_market_db.market_order_items idx_order_items_state_delivered"
  "thock_market_db.market_shipments idx_shipments_seller_created"
  "thock_payment_db.payment_payments idx_payment_order_id"
  "thock_product_db.products idx_products_seller_id"
  "thock_product_db.products idx_products_name"
  "thock_product_db.product_reviews idx_product_reviews_product_rating"
  "thock_settlement_db.sales_log idx_sales_log_status_settle"
  "thock_settlement_db.sales_log idx_sales_log_status_snapshot"
)

sqls=""
for e in "${INDEXES[@]}"; do sqls+="ALTER TABLE ${e% *} ALTER INDEX ${e#* } $vis; "; done
if [ "$mode" = before ]; then
  sqls+="CREATE INDEX idx_sales_log_status ON thock_settlement_db.sales_log (reconciliation_status); "
else
  sqls+="DROP INDEX idx_sales_log_status ON thock_settlement_db.sales_log; "
fi
docker exec -i thock-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" 2>&1 | grep -v "Using a password" || true' <<< "$sqls"
docker exec thock-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -t -e "
SELECT table_name, index_name, is_visible FROM information_schema.statistics
WHERE table_schema LIKE \"thock_%\" AND seq_in_index = 1
  AND (index_name IN (\"idx_sales_log_status\") OR index_name LIKE \"idx_orders_state%\" OR index_name IN
      (\"idx_orders_buyer_created\",\"idx_order_items_state_delivered\",\"idx_shipments_seller_created\",\"idx_payment_order_id\",
       \"idx_products_seller_id\",\"idx_products_name\",\"idx_product_reviews_product_rating\",\"idx_sales_log_status_settle\",\"idx_sales_log_status_snapshot\"))
ORDER BY table_name, index_name;" 2>&1 | grep -v "Using a password"'
