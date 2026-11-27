# 이번에 하지 않은 것과 이유

## 응답 형식이 바뀌어 보류 (프론트 수정 필요)

| 항목 | 지금 | 필요한 변경 |
|---|---|---|
| M2 내 주문 목록 | 구매자 주문 전체를 주문상품과 함께 한 번에(주문 9,213건 구매자 159ms) | 주문만 페이징 + 주문상품 batch fetch. 응답 `List` → `Page` |
| M4 판매자/관리자 배송 목록 | 판매자 배송 전체(최대 5.4만 건), 관리자는 94만 건 전체 | 페이징. 응답 `List` → `Page` |
| M9 지갑/수익/결제 이력 | `findByWalletId`, `findByBuyerId` 가 정렬·페이징 없이 전체 반환. 인덱스(FK)는 있음 | `Pageable` + `(wallet_id, id)` 정렬 |
| M1 카테고리 목록 count | 목록 요청마다 50만 건 count(75~140ms) | count 캐시, 또는 `Slice`(totalElements/totalPages 없어짐) |

## 측정했지만 효과가 없어 적용하지 않음

- **M2 `(buyer_id, created_at)` 인덱스**: 빨라지지 않고 주문이 많은 구매자는 느려짐 → [M2-my-orders.md](M2-my-orders.md)
- **M5 정리 DELETE 조건 변경**: 실제 DELETE 계획/시간이 전후 같음 → [M5-outbox.md](M5-outbox.md)
- **H3 FULLTEXT(ngram)**: 흔한 키워드에서 10~20배 느려짐 → [H3-product-search.md](H3-product-search.md)

## 비용이 작거나 스키마 변경 범위가 커서 보류

- **M5 Outbox `DEAD` 상태 분리**: 재시도 한도 초과 FAILED 조회가 지금 5ms 수준. `status` 컬럼이 MySQL ENUM 이라 상태를 추가하면
  Outbox 를 쓰는 모든 서비스 테이블을 바꿔야 한다
- **H2 `order_id` UNIQUE**: 같은 주문번호 결제가 2건 저장된 데이터가 있다(`ORDER-20260222-3CEA5BC19C9E`). 중복 정리와 결제 생성 멱등성 점검이 먼저

## 분석 문서의 "낮음" 항목

| 항목 | 상태 |
|---|---|
| 쓰이지 않는 인덱스(`idx_seller_order`, `idx_seller_state`, `idx_login_history_member_id`, `idx_inbox_topic_consumer_group`) | 미처리. 삭제 전에 운영에서 `sys.schema_unused_indexes` 로 충분한 기간 확인 필요(로컬 부하 몇 분으로는 판단 불가) |
| 호출 안 되는 리포지토리 메서드 | 미처리 |
| `existsByAggregateIdAndEventTypeAndStatus` 인덱스 | 호출하는 곳이 없어 그대로 |
| `RefreshToken (member_id, revoked_at)` | 미처리. member-service 는 이번 측정 대상에서 뺐다 |
| `PESSIMISTIC_WRITE` + `join fetch` 잠금 범위 | 미처리 |
| `open-in-view`, Hikari, 배치 insert | 미처리. 참고로 분석 문서의 "market Hikari 10개"는 틀렸다. 실제 설정은 `maximum-pool-size: 16` |

## 테스트 데이터 한계

- 상품명은 적은 단어(브랜드 8개, 모델 6개, 스위치 5개, 배열 4개 + 모델 번호)를 조합해 만들었다. 실제 카탈로그보다 단어가 훨씬 단조롭다.
  H3 의 "결과 건수별" 판단 기준(2만 건)은 이 분포에서 잰 값이다
- 스케줄러 대상(타임아웃, 정리, 자동 구매 확정)은 적재 시점에 쌓인 데이터라 "첫 실행" 상황에 가깝다. 평상시(하루치)도 따로 쟀다(H1)
- 로컬 Docker MySQL 1대, 애플리케이션 4개, k6 를 한 노트북(Apple Silicon)에서 같이 돌렸다. 절대값보다 같은 조건의 전/후 비율을 봐야 한다
