#!/usr/bin/env bash
# 슬로우 쿼리 로그 / performance_schema 통계를 측정 단위(label)로 켜고 끄는 스크립트.
#
# 사용:
#   ./slowlog.sh on <label>      # 새 로그 파일(slow-<label>.log)로 기록 시작 + digest/히스토그램 통계 초기화
#   ./slowlog.sh off             # 기록 중지 (long_query_time 은 기본값 10초로 되돌림)
#   ./slowlog.sh status          # 현재 설정 확인
#
# 주의: long_query_time 의 GLOBAL 값은 "새로 맺는 커넥션"에만 적용된다.
#       on 을 실행한 뒤에 애플리케이션을 (재)시작해야 HikariCP 커넥션이 0.1초 기준을 갖는다.
set -euo pipefail

q() { docker exec thock-mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -t -e \"$1\" 2>&1 | grep -v 'Using a password' || true"; }

case ${1:-status} in
  on)
    label=${2:?label 이 필요합니다}
    q "SET GLOBAL slow_query_log = OFF;
       SET GLOBAL slow_query_log_file = '/var/lib/mysql/slow-${label}.log';
       SET GLOBAL long_query_time = 0.1;
       SET GLOBAL log_slow_extra = ON;
       SET GLOBAL log_queries_not_using_indexes = OFF;
       SET GLOBAL slow_query_log = ON;
       TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;
       TRUNCATE TABLE performance_schema.events_statements_histogram_global;
       TRUNCATE TABLE performance_schema.events_statements_histogram_by_digest;"
    ;;
  off)
    q "SET GLOBAL slow_query_log = OFF; SET GLOBAL long_query_time = 10;"
    ;;
esac

q "SHOW GLOBAL VARIABLES WHERE Variable_name IN
   ('slow_query_log','long_query_time','slow_query_log_file','log_output','log_slow_extra',
    'min_examined_row_limit','log_queries_not_using_indexes');"
