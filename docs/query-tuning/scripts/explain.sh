#!/usr/bin/env bash
# 쿼리 하나의 실행 계획과 실제 실행 시간을 잰다.
#
# 사용: ./explain.sh <db> "<SQL>"
#   1) EXPLAIN            : 옵티마이저가 고른 계획 (type, key, rows, filtered, Extra)
#   2) EXPLAIN ANALYZE    : 실제로 실행하고 단계별 실제 시간/행 수 (트리)
#   3) 서버 실행 시간     : 같은 커넥션에서 워밍업 1회 + 5회 실행 후,
#                           performance_schema.events_statements_history 의 TIMER_WAIT(서버가 그 문장에 쓴 시간) 중앙값
#
# 왜 3)을 따로 재나:
#   EXPLAIN ANALYZE 의 actual time 은 "실행 단계"만 잰다. FULLTEXT count 처럼 옵티마이저 단계에서 답을 구하는 쿼리
#   ("Select tables optimized away")는 0ms 로 보인다. TIMER_WAIT 는 파싱~최적화~실행~결과 전송까지 포함하며
#   슬로우 로그의 Query_time 과 같은 기준이다.
#
# 주의: EXPLAIN ANALYZE 와 3)은 쿼리를 실제로 실행한다. DELETE/UPDATE 는 넣지 말 것(같은 조건의 SELECT 로 바꿔서 측정).
set -euo pipefail
db=${1:?db}; sql=${2:?sql}

run() { docker exec -i thock-mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" $1 $db 2>&1 | grep -v 'Using a password' ${2:-} || true"; }

echo "## EXPLAIN"
echo "EXPLAIN $sql" | run "-t"
echo "## EXPLAIN ANALYZE"
echo "EXPLAIN ANALYZE $sql" | run "-N -r"

# 결과 행은 컨테이너 안에서 버리고(tail) 마지막 타이밍 5줄만 받는다.
times=$( { for i in 0 1 2 3 4 5; do echo "$sql;"; done
           echo "SELECT ROUND(TIMER_WAIT/1e9, 3) FROM performance_schema.events_statements_history
                 WHERE THREAD_ID = PS_CURRENT_THREAD_ID() AND SQL_TEXT NOT LIKE '%events_statements_history%'
                 ORDER BY EVENT_ID DESC LIMIT 5;"; } | run "-N" "| tail -n 5" | tr '\n' ' ')
median=$(echo "$times" | tr ' ' '\n' | grep -v '^$' | sort -g | sed -n 3p)
echo "## median_ms=$median  (server time, runs: $times)"
