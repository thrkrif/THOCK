package com.thock.back.settlement.reconciliation.app.useCase;

import com.thock.back.settlement.reconciliation.domain.PgSalesRaw;
import com.thock.back.settlement.reconciliation.domain.ReconciliationResult;
import com.thock.back.settlement.reconciliation.domain.ReconciliationMismatchLog;
import com.thock.back.settlement.reconciliation.domain.SalesLog;
import com.thock.back.settlement.reconciliation.domain.enums.MismatchType;
import com.thock.back.settlement.reconciliation.domain.enums.PgStatus;
import com.thock.back.settlement.reconciliation.domain.enums.ReconciliationStatus;
import com.thock.back.settlement.reconciliation.out.ReconciliationJobRepository;
import com.thock.back.settlement.reconciliation.out.ReconciliationMismatchLogRepository;
import com.thock.back.settlement.reconciliation.out.PgSalesRawRepository;
import com.thock.back.settlement.reconciliation.out.SalesLogRepository;
import com.thock.back.settlement.shared.enums.TransactionType;
import com.thock.back.settlement.shared.money.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RunReconciliationUseCase {
    private final SalesLogRepository salesLogRepository;
    private final PgSalesRawRepository pgSalesRawRepository;
    private final ReconciliationJobRepository jobRepository;
    private final ReconciliationMismatchLogRepository mismatchLogRepository;


    @Transactional
    public void execute(LocalDate date) {
        log.info("=========[대사 시작] 기준일: {} =========", date);

        // 대사 결과를 담는 job 생성
        ReconciliationResult job = ReconciliationResult.builder().
                baseDate(date).build();
        jobRepository.save(job);

        // PG 데이터 조회
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);
        List<PgSalesRaw> pgList = pgSalesRawRepository.findAllByTransactedAtBetween(startOfDay, endOfDay);

        // 부분 환불, 부분 확정을 도입하면서 PG사의 데이터를 일대일로 비교할 수 없게됐음.
        // 이에 따라 주문번호 + 가격으로 그루핑 해서 map에 저장 후, 그 값을 DB 값과 비교.
        // TODO: MVP 단계에서는 대용량을 생각하지 말고, 기능구현에 집중. 이후 Batch 도입 고려
        // TODO: 대사의 과정이 절차적으로 표현되어 있어 가독성 떨어지니, 메소드 단위로 구분해 클린코드 만들기

        Map<String, Long> pgSumMap = pgList.stream()
                .collect(Collectors.groupingBy(
                        pg -> pg.getMerchantUid() + "_" + pg.getPgStatus().name(),
                        Collectors.summingLong(pg -> pg.getPaymentAmount().amount())
                ));

        int successCount = 0;
        int mismatchCount = 0;

        // 중복 검사 체크용 리스트
        List<String> processedKeys = pgSumMap.keySet().stream().toList();

        // [M7] PG 건마다 주문번호로 SalesLog 를 1~2번씩 조회하던 N+1 을 없애기 위해,
        // 이날 PG 데이터에 나온 주문번호의 SalesLog 를 IN 조회로 한 번에(청크 단위) 읽어 메모리에서 묶는다.
        // findByOrderNoAndTransactionType(no, type) 결과 = findByOrderNo(no) 결과를 type 으로 거른 것과 같다.
        Map<String, List<SalesLog>> logsByOrderNo = loadSalesLogsByOrderNo(
                pgList.stream().map(PgSalesRaw::getMerchantUid).collect(Collectors.toCollection(LinkedHashSet::new))
        );
        // 로그용 대표 PG 건: 키(주문번호_상태)별 첫 번째 PG 건 (기존: 키마다 pgList 전체를 다시 훑음)
        Map<String, PgSalesRaw> samplePgByKey = new HashMap<>();
        for (PgSalesRaw pg : pgList) {
            samplePgByKey.putIfAbsent(pg.getMerchantUid() + "_" + pg.getPgStatus().name(), pg);
        }

        // 여기서 key = 주문번호_PG상태
        for (String key : processedKeys) {
            String[] split = key.split("_");
            String merchantUid = split[0];
            PgStatus pgStatus = PgStatus.valueOf(split[1]);
            Long pgTotalAmount = pgSumMap.get(key);

            //
            TransactionType targetType = convertToTransactionType(pgStatus);
            if (targetType == null) continue;

            List<SalesLog> sameOrderLogs = logsByOrderNo.getOrDefault(merchantUid, List.of());
            List<SalesLog> internalLogs = sameOrderLogs.stream()
                    .filter(salesLog -> salesLog.getTransactionType() == targetType)
                    .toList();

            if (internalLogs.isEmpty()) {
                // 대표로 사용할 PG Raw 데이터 하나 찾기 (로그용)
                PgSalesRaw samplePg = samplePgByKey.get(key);

                if (!sameOrderLogs.isEmpty()) {
                    sameOrderLogs.forEach(SalesLog::mismatchReconciliation);
                    long internalTotal = sameOrderLogs.stream()
                            .mapToLong(log -> log.getPaymentAmount().amount())
                            .sum();
                    saveMismatchLog(
                            job,
                            samplePg,
                            sameOrderLogs.get(0),
                            Money.of(internalTotal),
                            MismatchType.STATUS_DIFF,
                            "상태 불일치"
                    );
                } else {
                    saveMismatchLog(job, samplePg, null, Money.zero(), MismatchType.PG_ONLY, "주문서 누락");
                }
                mismatchCount++;
                continue;
            }

            long dbSum = internalLogs.stream().mapToLong(log -> log.getPaymentAmount().amount()).sum();

            if (pgTotalAmount == Math.abs(dbSum)) {
                internalLogs.forEach(SalesLog::matchReconciliation);
                successCount++;
            } else {
                internalLogs.forEach(SalesLog::mismatchReconciliation);
                PgSalesRaw samplePg = samplePgByKey.get(key);

                // [수정 2 핵심] 첫 번째 항목의 금액이 아닌, 합산된 금액(dbSum)을 파라미터로 넘김
                saveMismatchLog(job, samplePg, internalLogs.get(0), Money.of(dbSum), MismatchType.AMOUNT_DIFF, "금액 불일치");
                mismatchCount++;
            }
        }

        List<SalesLog> remainLogs = salesLogRepository.findAllBySnapshotAtBetweenAndReconciliationStatus(
                startOfDay,
                endOfDay,
                ReconciliationStatus.PENDING
        );

        for (SalesLog remainLog : remainLogs) {
            remainLog.mismatchReconciliation();
            saveMismatchLog(job, null, remainLog, remainLog.getPaymentAmount(), MismatchType.INTERNAL_ONLY, "PG 내역 없음");
            mismatchCount++;
        }

        job.finish(processedKeys.size(), successCount, mismatchCount);
        log.info("=========[대사 종료] 성공: {}, 실패: {}", successCount, mismatchCount);
    }

    // IN 절 파라미터가 너무 길어지지 않도록 1,000개씩 나눠 조회한다.
    private static final int ORDER_NO_CHUNK_SIZE = 1000;

    private Map<String, List<SalesLog>> loadSalesLogsByOrderNo(Collection<String> orderNos) {
        Map<String, List<SalesLog>> result = new HashMap<>();
        List<String> all = new ArrayList<>(orderNos);
        for (int from = 0; from < all.size(); from += ORDER_NO_CHUNK_SIZE) {
            List<String> chunk = all.subList(from, Math.min(from + ORDER_NO_CHUNK_SIZE, all.size()));
            for (SalesLog salesLog : salesLogRepository.findByOrderNoIn(chunk)) {
                result.computeIfAbsent(salesLog.getOrderNo(), k -> new ArrayList<>()).add(salesLog);
            }
        }
        return result;
    }

    private TransactionType convertToTransactionType(PgStatus pgStatus) {
        if (pgStatus == PgStatus.PAID) {
            return TransactionType.PAYMENT;
        }
        if (pgStatus == PgStatus.CANCELED) {
            return TransactionType.REFUND;
        }
        return null;
    }

    private void saveMismatchLog(ReconciliationResult job, PgSalesRaw pg, SalesLog internal,
                                 Money calculatedInternalAmount, MismatchType type, String reason) {

        String orderNo = (pg != null) ? pg.getMerchantUid() : internal.getOrderNo();
        String pgKey = (pg != null) ? pg.getPgKey() : null;
        Money pgAmount = (pg != null && pg.getPaymentAmount() != null) ? pg.getPaymentAmount() : Money.zero();

        ReconciliationMismatchLog log = ReconciliationMismatchLog.builder()
                .job(job)
                .orderNo(orderNo)
                .pgKey(pgKey)
                .type(type)
                .pgAmount(pgAmount)
                .internalAmount(calculatedInternalAmount) // 합산된 금액으로 저장
                .reason(reason)
                .build();
        mismatchLogRepository.save(log);
    }
}
