#!/usr/bin/env bash
# 측정용으로 product / market / payment / settlement 서비스 4개를 local 프로파일로 띄우고 내리는 스크립트.
# member-service 와 api-gateway 는 띄우지 않는다. 대신 k6 가 게이트웨이가 붙여 주는 헤더
# (X-Gateway-Auth, X-Member-Id/Role/State)를 직접 붙여서 각 서비스를 바로 호출한다.
#
# 사용:
#   ./services.sh start <코드 루트(빌드된 jar 가 있는 곳)> <로그 폴더>
#   ./services.sh stop
#
# DB 비밀번호는 thock-mysql 컨테이너의 환경변수에서 읽어 프로세스 환경으로만 넘긴다(화면에 출력하지 않음).
set -euo pipefail

SERVICES="product:8082 market:8083 payment:8084 settlement:8085"
PERF_GATEWAY_SECRET=${PERF_GATEWAY_SECRET:-perf-local-gateway-secret}

start() {
  local root=${1:?코드 루트 필요} logdir=${2:?로그 폴더 필요}
  mkdir -p "$logdir"
  local dbpw; dbpw=$(docker exec thock-mysql printenv MYSQL_ROOT_PASSWORD)
  for sp in $SERVICES; do
    local svc=${sp%%:*} port=${sp##*:}
    local jar; jar=$(ls "$root/$svc-service/build/libs/$svc-service-"*-SNAPSHOT.jar | grep -v plain)
    SPRING_PROFILES_ACTIVE=local \
    SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD="$dbpw" \
    JWT_SECRET_KEY=thock-local-jwt-secret-key-which-is-long-enough-32chars \
    TOSS_PAYMENTS_SECRET_KEY=test_sk_perf_dummy \
    SECURITY_GATEWAY_INTERNAL_SECRET="$PERF_GATEWAY_SECRET" \
    SECURITY_SERVICE_INTERNAL_SECRET="$PERF_GATEWAY_SECRET" \
      nohup java -Xms512m -Xmx1g -jar "$jar" > "$logdir/$svc.log" 2>&1 &
    echo "$!" > "$logdir/$svc.pid"
    echo "started $svc (pid $!, port $port)"
  done
  for sp in $SERVICES; do
    local svc=${sp%%:*} port=${sp##*:} waited=0
    until curl -sf "http://localhost:$port/actuator/health" >/dev/null 2>&1; do
      sleep 3; waited=$((waited+3))
      if [ $waited -ge 240 ]; then echo "[ERROR] $svc 가 4분 안에 뜨지 않음. $logdir/$svc.log 확인"; exit 1; fi
    done
    echo "ready   $svc (${waited}s)"
  done
}

stop() {
  for port in 8082 8083 8084 8085; do
    local pid; pid=$(lsof -ti tcp:$port -sTCP:LISTEN 2>/dev/null || true)
    [ -n "$pid" ] && kill $pid && echo "stopped port $port (pid $pid)"
  done
  sleep 5
}

case ${1:-} in
  start) start "${2:-}" "${3:-}" ;;
  stop)  stop ;;
  *) echo "usage: $0 start <root> <logdir> | stop"; exit 1 ;;
esac
