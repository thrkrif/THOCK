// 쿼리 튜닝 전/후 비교용 부하 스크립트 (docs/query-tuning 참고)
//
// 게이트웨이를 거치지 않고 각 서비스를 바로 호출한다. 게이트웨이가 붙여 주는 인증 헤더
// (X-Gateway-Auth, X-Member-Id/Role/State)를 직접 붙인다. GATEWAY_SECRET 은 services.sh 와 같은 값.
//
// 실행: k6 run -e LABEL=baseline -e OUT=<폴더> k6/query-tuning.js
//
// 시나리오는 겹치지 않게 순서대로 실행된다(startTime). 같은 스크립트를 전/후에 똑같이 돌려 비교한다.
import http from 'k6/http';
import { check } from 'k6';

const PRODUCT = __ENV.PRODUCT_URL || 'http://localhost:8082';
const MARKET = __ENV.MARKET_URL || 'http://localhost:8083';
const SECRET = __ENV.GATEWAY_SECRET || 'perf-local-gateway-secret';

// 데이터 분포(docs/perf-seed) 기준으로 고른 대상
const MID_SELLER = 100;   // 상품 1,495개, 배송 2,758건
const HEAVY_SELLER = 4;   // 상품 28,882개
const MID_BUYER = 100;    // 주문 475건

function auth(memberId, role) {
  return {
    headers: {
      'X-Gateway-Auth': SECRET,
      'X-Member-Id': String(memberId),
      'X-Member-Role': role,
      'X-Member-State': 'ACTIVE',
    },
  };
}

function scenario(exec, startTime, vus, duration) {
  return { executor: 'constant-vus', exec, startTime, vus, duration, gracefulStop: '10s' };
}

const ENDPOINTS = [
  'product_list', 'review_summaries', 'product_list_sort_price', 'product_list_size_2000',
  'search_common', 'search_rare', 'search_none',
  'my_products_mid', 'my_products_heavy', 'my_orders', 'seller_shipments',
];

export const options = {
  scenarios: {
    productList:   scenario('productList',   '0s',   5, '30s'),
    sortByPrice:   scenario('sortByPrice',   '40s',  2, '30s'),
    bigPage:       scenario('bigPage',       '80s',  1, '20s'),
    search:        scenario('search',        '110s', 3, '45s'),
    myProducts:    scenario('myProducts',    '165s', 3, '30s'),
    myOrders:      scenario('myOrders',      '205s', 2, '30s'),
    shipments:     scenario('shipments',     '245s', 1, '30s'),
  },
  // 엔드포인트별 p95 가 요약에 나오도록 태그별 threshold 를 건다(항상 통과하는 값).
  thresholds: Object.fromEntries(ENDPOINTS.map((n) => [`http_req_duration{name:${n}}`, ['p(95)<600000']])),
  summaryTrendStats: ['avg', 'med', 'p(95)', 'max', 'count'],
};

function get(url, name, params = {}) {
  const res = http.get(url, Object.assign({ tags: { name } }, params));
  check(res, { [`${name} 200`]: (r) => r.status === 200 });
  return res;
}

export function productList() {
  const res = get(`${PRODUCT}/api/v1/products?category=KEYBOARD&page=0&size=12&sort=id,desc`, 'product_list');
  // 프론트(HomePage)는 목록을 받은 뒤 그 상품들의 리뷰 요약을 한 번 더 요청한다.
  const ids = (res.json('content') || []).map((p) => p.id ?? p.productId).filter(Boolean);
  if (ids.length > 0) {
    get(`${PRODUCT}/api/v1/products/reviews/summaries?productIds=${ids.join(',')}`, 'review_summaries');
  }
}

export function sortByPrice() {
  const page = Math.floor(Math.random() * 5);
  get(`${PRODUCT}/api/v1/products?category=KEYBOARD&page=${page}&size=12&sort=price,asc`, 'product_list_sort_price');
}

export function bigPage() {
  get(`${PRODUCT}/api/v1/products?category=KEYBOARD&page=0&size=2000&sort=id,desc`, 'product_list_size_2000');
}

export function search() {
  // common: 이름에 들어간 브랜드(전체의 약 1/8), rare: 모델 번호(1건), none: 결과 없음
  const r = Math.random();
  if (r < 0.4) {
    get(`${PRODUCT}/api/v1/products/search?keyword=Keychron&page=0&size=12&sort=id,desc`, 'search_common');
  } else if (r < 0.8) {
    const model = 1000 + Math.floor(Math.random() * 400000);
    get(`${PRODUCT}/api/v1/products/search?keyword=M${model}%20&page=0&size=12&sort=id,desc`, 'search_rare');
  } else {
    get(`${PRODUCT}/api/v1/products/search?keyword=Cherry&page=0&size=12&sort=id,desc`, 'search_none');
  }
}

export function myProducts() {
  if (Math.random() < 0.5) {
    get(`${PRODUCT}/api/v1/products/me?page=0&size=10`, 'my_products_mid', auth(MID_SELLER, 'SELLER'));
  } else {
    get(`${PRODUCT}/api/v1/products/me?page=0&size=10`, 'my_products_heavy', auth(HEAVY_SELLER, 'SELLER'));
  }
}

export function myOrders() {
  get(`${MARKET}/api/v1/orders`, 'my_orders', auth(MID_BUYER, 'USER'));
}

export function shipments() {
  get(`${MARKET}/api/v1/shipments/me`, 'seller_shipments', auth(MID_SELLER, 'SELLER'));
}

export function handleSummary(data) {
  const rows = ENDPOINTS.map((n) => {
    const m = data.metrics[`http_req_duration{name:${n}}`];
    if (!m) return `${n.padEnd(26)} (no data)`;
    const v = m.values;
    return `${n.padEnd(26)} count=${String(v.count).padStart(5)}  avg=${v.avg.toFixed(1).padStart(8)}ms  ` +
      `med=${v.med.toFixed(1).padStart(8)}ms  p95=${v['p(95)'].toFixed(1).padStart(8)}ms  max=${v.max.toFixed(1).padStart(8)}ms`;
  });
  const failed = data.metrics.http_req_failed ? (data.metrics.http_req_failed.values.rate * 100).toFixed(2) : 'n/a';
  const text = [`# k6 ${__ENV.LABEL || ''}`, ...rows, `http_req_failed=${failed}%`, ''].join('\n');
  const out = __ENV.OUT || '.';
  return {
    stdout: text,
    [`${out}/k6-${__ENV.LABEL || 'run'}.txt`]: text,
    [`${out}/k6-${__ENV.LABEL || 'run'}.json`]: JSON.stringify(data, null, 2),
  };
}
