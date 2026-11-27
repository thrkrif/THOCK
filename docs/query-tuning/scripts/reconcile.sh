#!/usr/bin/env bash
# 대사(reconciliation) 배치를 실제 API로 실행하고 걸린 시간을 잰다. (M7, M6 측정용)
# 대사는 sales_log 상태를 바꾸므로, 전/후를 같은 조건에서 돌리려고 그 날짜 데이터를 백업했다가 되돌린다.
#
# 사용:
#   ./reconcile.sh backup  2026-10-15   # 그 날짜 sales_log 상태 백업 + 대사 결과 테이블 기준점 기록
#   ./reconcile.sh run     2026-10-15   # PG 원장 생성 → 대사 실행, 각 단계 소요 시간 출력
#   ./reconcile.sh restore 2026-10-15   # 백업한 상태로 되돌리고 이번 실행이 만든 행 삭제
set -euo pipefail
cmd=${1:?backup|run|restore}; day=${2:?YYYY-MM-DD}
SETTLEMENT=${SETTLEMENT_URL:-http://localhost:8085}

q() { docker exec thock-mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -t thock_settlement_db -e \"$1\" 2>&1 | grep -v 'Using a password' || true"; }

case $cmd in
  backup)
    q "CREATE DATABASE IF NOT EXISTS perf_tmp;
       DROP TABLE IF EXISTS perf_tmp.sales_log_day;
       CREATE TABLE perf_tmp.sales_log_day AS
         SELECT id, reconciliation_status, updated_at FROM sales_log
         WHERE snapshot_at >= '$day' AND snapshot_at < '$day' + INTERVAL 1 DAY;
       DROP TABLE IF EXISTS perf_tmp.recon_marks;
       CREATE TABLE perf_tmp.recon_marks AS
         SELECT (SELECT COALESCE(MAX(id), 0) FROM reconciliation_job) AS job_max,
                (SELECT COALESCE(MAX(id), 0) FROM reconciliation_mismatch_log) AS mismatch_max;
       SELECT COUNT(*) AS backed_up_rows FROM perf_tmp.sales_log_day;"
    ;;
  run)
    echo "generate-pg-from-saleslog: $(curl -s -o /dev/null -w '%{http_code} %{time_total}s' -X POST "$SETTLEMENT/api/v1/settlements/reconciliation/manual/generate-pg-from-saleslog?targetDate=$day")"
    echo "run-reconciliation      : $(curl -s -o /dev/null -w '%{http_code} %{time_total}s' -X POST "$SETTLEMENT/api/v1/settlements/reconciliation/manual/run-reconciliation?targetDate=$day")"
    q "SELECT reconciliation_status, COUNT(*) FROM sales_log
       WHERE snapshot_at >= '$day' AND snapshot_at < '$day' + INTERVAL 1 DAY GROUP BY 1;
       SELECT id, base_date, total_count, match_count, mismatch_count FROM reconciliation_job ORDER BY id DESC LIMIT 1;"
    ;;
  restore)
    q "UPDATE sales_log s JOIN perf_tmp.sales_log_day b ON b.id = s.id
         SET s.reconciliation_status = b.reconciliation_status, s.updated_at = b.updated_at;
       DELETE FROM pg_sales_raw WHERE transacted_at >= '$day' AND transacted_at < '$day' + INTERVAL 1 DAY;
       DELETE FROM reconciliation_mismatch_log WHERE id > (SELECT mismatch_max FROM perf_tmp.recon_marks);
       DELETE FROM reconciliation_job WHERE id > (SELECT job_max FROM perf_tmp.recon_marks);
       SELECT reconciliation_status, COUNT(*) FROM sales_log
       WHERE snapshot_at >= '$day' AND snapshot_at < '$day' + INTERVAL 1 DAY GROUP BY 1;"
    ;;
esac
