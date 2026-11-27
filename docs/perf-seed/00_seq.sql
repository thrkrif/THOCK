-- 1..1,000,000 숫자 테이블 (적재 후 99_cleanup.sql 로 삭제)
CREATE DATABASE IF NOT EXISTS perf_tmp;
DROP TABLE IF EXISTS perf_tmp.digits;
CREATE TABLE perf_tmp.digits (d TINYINT PRIMARY KEY);
INSERT INTO perf_tmp.digits VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
DROP TABLE IF EXISTS perf_tmp.seq;
CREATE TABLE perf_tmp.seq (n INT PRIMARY KEY);
INSERT INTO perf_tmp.seq
SELECT f.d*100000 + e.d*10000 + d4.d*1000 + c.d*100 + b.d*10 + a.d + 1
FROM perf_tmp.digits f, perf_tmp.digits e, perf_tmp.digits d4,
     perf_tmp.digits c, perf_tmp.digits b, perf_tmp.digits a;
