// loadtest/scripts/order-concurrency.js
//
// Phase 3 Task 3-4 시나리오 2: 1,000 VU 동시 주문 정합성 검증.
// 경합 상품 product_id 1001..1010 (각 재고 100, 총 1000) 에 대해 VU 가 1개씩 주문 시도.
// 오버셀링 0건 / 재고 0 도달 시 실패 처리 / 정합성 100% 검증.
//
// 정합성 불변식: VU 당 주문 시도는 정확히 1회 (__ITER === 0). 1m hold 구간은
// Grafana 타임라인 관찰용으로 VU 를 살아있게만 유지하고 추가 주문은 발생시키지 않는다.
// verify-concurrency.sql 의 "seed 적용 직후 시나리오 2 1회 실행" 전제와 정합.
//
// 실행 (로컬 리허설):
//   mkdir -p loadtest/reports/local
//   k6 run --vus 10 --duration 30s \
//     --summary-export=loadtest/reports/local/k6-summary.json \
//     -e BASE_URL=http://localhost:8080 \
//     loadtest/scripts/order-concurrency.js
//
// 실행 (세션 C, Prometheus remote-write):
//   k6 run --summary-export=loadtest/reports/YYYY-MM-DD/k6-summary.json \
//     -e BASE_URL=http://<internal-lb>:8080 \
//     -o experimental-prometheus-rw=http://<prom-addr>:9090/api/v1/write \
//     loadtest/scripts/order-concurrency.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';

const users = new SharedArray('users', function () {
  const csv = open('./users.csv');
  return papaparse.parse(csv, { header: true, skipEmptyLines: true }).data;
});

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const authFailures = new Counter('peekcart_auth_failures');
const cartFailures = new Counter('peekcart_cart_failures');

export const options = {
  scenarios: {
    contention: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 1000 },
        { duration: '1m',  target: 1000 },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    'http_req_failed{scenario:contention}': ['rate<0.1'],
    'peekcart_auth_failures': ['count<10'],
  },
  discardResponseBodies: false,
};

const JSON_HEADERS = { 'Content-Type': 'application/json', 'Accept': 'application/json' };

export default function () {
  if (__ITER > 0) {
    sleep(10);
    return;
  }

  const user = users[(__VU - 1) % users.length];

  const loginRes = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email: user.email, password: user.password }),
    { headers: JSON_HEADERS, tags: { name: 'login' } },
  );

  let accessToken = null;
  try {
    accessToken = loginRes.json('data.accessToken');
  } catch (_) {
    accessToken = null;
  }

  const loginOk = check(loginRes, {
    'login 200 + token present': (r) => r.status === 200 && !!accessToken,
  });
  if (!loginOk) {
    authFailures.add(1);
    return;
  }

  const authHeaders = { ...JSON_HEADERS, Authorization: `Bearer ${accessToken}` };
  const productId = 1001 + Math.floor(Math.random() * 10);

  const cartRes = http.post(
    `${BASE_URL}/api/v1/cart/items`,
    JSON.stringify({ productId, quantity: 1 }),
    { headers: authHeaders, tags: { name: 'cart' } },
  );
  const cartOk = check(cartRes, { 'cart 201': (r) => r.status === 201 });
  if (!cartOk) {
    cartFailures.add(1);
    return;
  }

  const orderRes = http.post(
    `${BASE_URL}/api/v1/orders`,
    JSON.stringify({
      receiverName: 'Load Tester',
      phone: '010-0000-0000',
      zipcode: '06236',
      address: 'Seoul Gangnam Loadtest',
    }),
    { headers: authHeaders, tags: { name: 'order' } },
  );
  check(orderRes, {
    'order 201|409|400': (r) => r.status === 201 || r.status === 409 || r.status === 400,
  });
}
