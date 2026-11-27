#!/usr/bin/env bash
# 한 번의 측정(label)이 끝난 뒤 결과를 모은다.
#   1) 슬로우 로그 파일 원본           -> <out>/slow-<label>.log
#   2) mysqldumpslow 요약(총 시간 순)   -> <out>/slow-<label>.summary.txt
#   3) digest 통계 상위 40개            -> <out>/digest-<label>.txt
#   4) 전체 쿼리 실행시간 히스토그램    -> <out>/histogram-<label>.txt
#
# 사용: ./collect.sh <label> <out 폴더>
# mysqldumpslow 는 호스트에 설치된 것을 쓴다 (brew install mysql 또는 mysql-client 에 포함).
set -euo pipefail
label=${1:?label 필요}; out=${2:?out 폴더 필요}
mkdir -p "$out"

q() { docker exec thock-mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -t -e \"$1\" 2>&1 | grep -v 'Using a password' || true"; }

docker cp "thock-mysql:/var/lib/mysql/slow-${label}.log" "$out/slow-${label}.log"
# -s t: 총 실행 시간 순 정렬, -t 30: 상위 30개 패턴. 숫자/문자열 리터럴은 N, 'S' 로 묶인다.
mysqldumpslow -s t -t 30 "$out/slow-${label}.log" > "$out/slow-${label}.summary.txt" 2>/dev/null || true

q "SELECT SCHEMA_NAME AS db,
          COUNT_STAR AS exec_count,
          ROUND(AVG_TIMER_WAIT/1e9, 2) AS avg_ms,
          ROUND(MAX_TIMER_WAIT/1e9, 2) AS max_ms,
          ROUND(SUM_TIMER_WAIT/1e12, 2) AS total_sec,
          ROUND(SUM_ROWS_EXAMINED/COUNT_STAR) AS rows_exam_per_exec,
          ROUND(SUM_ROWS_SENT/COUNT_STAR) AS rows_sent_per_exec,
          SUM_NO_INDEX_USED AS no_index_used,
          LEFT(DIGEST_TEXT, 160) AS query
   FROM performance_schema.events_statements_summary_by_digest
   WHERE SCHEMA_NAME LIKE 'thock_%'
   ORDER BY SUM_TIMER_WAIT DESC
   LIMIT 40;" > "$out/digest-${label}.txt"

# 전체 문장 실행 시간 분포 (버킷 상한 기준 누적 비율). 슬로우 기준(100ms)의 근거로 사용.
q "SELECT CASE
            WHEN BUCKET_TIMER_HIGH <= 1e9   THEN '<= 1ms'
            WHEN BUCKET_TIMER_HIGH <= 1e10  THEN '<= 10ms'
            WHEN BUCKET_TIMER_HIGH <= 1e11  THEN '<= 100ms'
            WHEN BUCKET_TIMER_HIGH <= 1e12  THEN '<= 1s'
            ELSE '> 1s' END AS latency,
          SUM(COUNT_BUCKET) AS statements
   FROM performance_schema.events_statements_histogram_global
   GROUP BY latency
   ORDER BY MIN(BUCKET_TIMER_HIGH);" > "$out/histogram-${label}.txt"

echo "slow log entries: $(grep -c '^# Query_time' "$out/slow-${label}.log" || true)"
echo "saved to $out"
