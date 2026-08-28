#!/usr/bin/env python3
"""cross-service saga E2E (계획 P5~P11 · P16).

internal:true 네트워크 **안에서** 돈다 — 호스트에서 실행하려면 앱에 호스트 포트를 열어야
하고 그러면 격리 계약이 깨진다.

구동은 **실제 HTTP 진입점**이다. Kafka 에 직접 발행하거나 outbox 에 SQL 로 INSERT 하면
컨트롤러·도메인 전이·publisher 직렬화가 통째로 우회된다 — outbox 행은 이미 직렬화된
문자열이고 poller 는 그걸 그대로 싣기 때문이다(계획 V16).

usage:
  saga_e2e.py readiness
  saga_e2e.py scenario <a|b|c|d>
  saga_e2e.py negative-control <name>
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

import pymysql

RUN_ID = os.environ.get("E2E_RUN_ID", "local")
COMMIT_SHA = os.environ.get("E2E_COMMIT_SHA", "unknown")
OUT_DIR = os.environ.get("E2E_OUT_DIR", "/work/out")

# 계획 P19 절대 상한. 무한 대기는 CI 를 매달고, 너무 짧으면 도착 전인 것을 실패로 만든다.
# 실측: 부하가 걸린 로컬에서 예약 실패 체인(order.created → 예약 실패 → result → 취소)이
# 90s 를 넘긴 적이 있다 — 홉마다 붙는 consumer 지연이 누적된다.
DEADLINE = float(os.environ.get("E2E_SCENARIO_TIMEOUT", "180"))
POLL = 1.0

SERVICES = {
    "order": ("order-service", "peekcart_order"),
    "product": ("product-service", "peekcart_product"),
    "payment": ("payment-service", "peekcart_payment"),
    "notification": ("notification-service", "peekcart_notification"),
}

BUSINESS_TOPICS = [
    "order.created", "order.cancelled", "order.compensation.requested",
    "payment.requested", "payment.completed", "payment.failed", "payment.refunded",
    "product.updated", "stock.reservation.result", "stock.compensation.requested",
]

# DLQ listener readiness (계획 P5 ②). DlqTopology 의 group 은 '실패한 업무 consumer' 의
# group 이라 여기 쓸 수 없다 — 이건 DLQ listener 자신의 group 이다.
DLQ_INTAKE_GROUPS = [
    "order-svc-dlq-group", "product-svc-dlq-group",
    "payment-svc-dlq-group", "notification-svc-dlq-group",
]
DLQ_QUARANTINE_GROUPS = [
    "order-svc-dlq-quarantine-group", "product-svc-dlq-quarantine-group",
    "payment-svc-dlq-quarantine-group",
]


# ----------------------------------------------------------------- 인프라 접근

def db(service):
    _, schema = SERVICES[service]
    return pymysql.connect(host="mysql", user="peekcart_%s" % service,
                           password="peekcart_%s" % service, database=schema,
                           autocommit=True, cursorclass=pymysql.cursors.DictCursor)


def query(service, sql, args=()):
    with db(service) as conn, conn.cursor() as cur:
        cur.execute(sql, args)
        return cur.fetchall()


def root_query(sql, args=()):
    conn = pymysql.connect(host="mysql", user="root", password="root",
                           autocommit=True, cursorclass=pymysql.cursors.DictCursor)
    try:
        with conn.cursor() as cur:
            cur.execute(sql, args)
            return cur.fetchall()
    finally:
        conn.close()


def http(service, method, path, body=None, user_id=1, role="USER", expect=None):
    host, _ = SERVICES[service]
    url = "http://%s:8080%s" % (host, path)
    headers = {
        "Content-Type": "application/json",
        # DUAL_ACCEPT 전환기 헤더 — gateway 없이 실제 컨트롤러를 지나기 위해서다(계획 §2.1).
        "X-User-Id": str(user_id),
        "X-User-Role": role,
        "X-User-Family-Id": "e2e-family",
    }
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            payload = json.loads(r.read().decode("utf-8") or "{}")
            status = r.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        payload = json.loads(raw) if raw.strip().startswith("{") else {"raw": raw}
        status = e.code
    if expect is not None and status != expect:
        raise AssertionError("%s %s → %d (기대 %d): %s" % (method, path, status, expect, payload))
    return status, payload


def stub_ledger():
    with urllib.request.urlopen("http://pg-stub:8080/__ledger", timeout=10) as r:
        return json.loads(r.read().decode("utf-8"))["calls"]


# ----------------------------------------------------------------- 대기 유틸

class Timeout(AssertionError):
    pass


def wait_for(desc, fn, deadline=None):
    """조건이 참이 될 때까지 기다린다. **상한을 반드시 둔다** — 무한 대기는 CI 를 매달고,
    대기 없는 즉시 단언은 아직 도착하지 않은 것을 실패로 만든다."""
    limit = time.time() + (deadline or DEADLINE)
    last = None
    while time.time() < limit:
        last = fn()
        if last:
            return last
        time.sleep(POLL)
    raise Timeout("타임아웃(%ds): %s — 마지막 관측 %r" % (deadline or DEADLINE, desc, last))


# ----------------------------------------------------------------- readiness

def check_readiness():
    from kafka import KafkaAdminClient
    from kafka.errors import KafkaError

    result = {}
    admin = KafkaAdminClient(bootstrap_servers="kafka:29092", request_timeout_ms=10000)

    # (1) cold start — flyway 성공만으로는 warm reuse 를 구별하지 못한다(계획 P4).
    rows = root_query("SELECT run_id FROM peekcart_e2e_meta.run_marker WHERE id = 1")
    if not rows:
        raise AssertionError("run marker 부재 — init 이 돌지 않았다(재사용 volume 의심)")
    stored = rows[0]["run_id"]
    if stored != RUN_ID:
        raise AssertionError("warm volume — marker run_id=%s, 현재 run_id=%s" % (stored, RUN_ID))
    result["run_marker"] = stored

    # (2) migration 완전성 — (1) 과 독립 조건이다
    flyway = {}
    for svc, (_, schema) in SERVICES.items():
        rows = root_query(
            "SELECT success, COUNT(*) AS c FROM `%s`.flyway_schema_history GROUP BY success" % schema)
        by = {int(r["success"]): r["c"] for r in rows}
        if by.get(0):
            raise AssertionError("%s: 실패한 migration %d건" % (svc, by[0]))
        if not by.get(1):
            raise AssertionError("%s: 적용된 migration 이 없다" % svc)
        flyway[svc] = by[1]
    result["flyway_applied"] = flyway

    # (3) 앱 health.
    #     연결/이름해석 실패는 **재시도 대상**이다 — 폴링 시작 시점에 컨테이너 DNS 가 아직
    #     안 잡혀 있을 수 있다. http() 는 HTTPError 만 잡으므로 여기서 URLError 를 흡수하지
    #     않으면 일시적 실패 하나가 readiness 전체를 죽인다(실측).
    def health_ok(svc):
        try:
            return http(svc, "GET", "/actuator/health")[1].get("status") == "UP"
        except (urllib.error.URLError, OSError):
            return False

    for svc in SERVICES:
        wait_for("%s health" % svc, lambda s=svc: health_ok(s), 180)
    result["health"] = sorted(SERVICES)

    # (4) 토픽 — 업무 10 + 각 .dlq
    expected_topics = set(BUSINESS_TOPICS) | {t + ".dlq" for t in BUSINESS_TOPICS}

    def topics_ready():
        actual = set(admin.list_topics())
        return expected_topics.issubset(actual)

    wait_for("업무 토픽 10 + .dlq", topics_ready, 120)
    result["topics"] = len(expected_topics)

    # (5) consumer group — 존재가 아니라 **active member ≥ 1 이고 할당 partition ≥ 1**.
    #     존재만 보면 죽은 group 이 남아 있어도 준비 완료로 판정된다.
    required_groups = set(DLQ_INTAKE_GROUPS) | set(DLQ_QUARANTINE_GROUPS)
    with open(os.path.join(os.path.dirname(__file__), "business-groups.txt")) as f:
        required_groups |= {l.strip() for l in f if l.strip() and not l.startswith("#")}

    def groups_ready():
        try:
            described = admin.describe_consumer_groups(sorted(required_groups))
        except KafkaError:
            return False
        bad = []
        for g in described:
            members = getattr(g, "members", []) or []
            if not members:
                bad.append("%s: member 0" % g.group)
                continue
            assigned = 0
            for m in members:
                assignment = getattr(m, "member_assignment", None)
                parts = getattr(assignment, "assignment", []) if assignment else []
                assigned += sum(len(p[1]) for p in parts)
            if assigned < 1:
                bad.append("%s: 할당 partition 0" % g.group)
        groups_ready.bad = bad
        return not bad

    wait_for("consumer group active member/partition", groups_ready, 180)
    result["consumer_groups"] = len(required_groups)

    admin.close()
    return result


# ----------------------------------------------------------------- 시나리오 공통

def seed_category(name="e2e"):
    with db("product") as conn, conn.cursor() as cur:
        cur.execute("SELECT id FROM categories WHERE name = %s", (name,))
        row = cur.fetchone()
        if row:
            return row["id"]
        # categories 는 (id, name, parent_id) 뿐이다 — 타임스탬프 컬럼이 없다.
        cur.execute("INSERT INTO categories (name) VALUES (%s)", (name,))
        cur.execute("SELECT LAST_INSERT_ID() AS id")
        return cur.fetchone()["id"]


def create_product(scenario_id, price, stock):
    """실제 admin API — publishProductUpdated 까지 지난다(order-service 단가 캐시의 입력)."""
    category_id = seed_category()
    _, payload = http("product", "POST", "/api/v1/admin/products", {
        "categoryId": category_id,
        "name": "e2e-%s-%s" % (scenario_id, RUN_ID),
        "description": "saga e2e",
        "price": price,
        "imageUrl": "http://example.invalid/x.png",
        "stock": stock,
    }, role="ADMIN", expect=201)
    return payload["data"]["id"]


def place_order(scenario_id, user_id, product_id, quantity):
    http("order", "POST", "/api/v1/cart/items",
         {"productId": product_id, "quantity": quantity}, user_id=user_id, expect=201)
    status, payload = http("order", "POST", "/api/v1/orders", {
        "receiverName": "e2e-%s" % scenario_id,
        "phone": "010-0000-0000",
        "zipcode": "00000",
        "address": "e2e",
    }, user_id=user_id)
    if status != 201:
        raise AssertionError("주문 생성 실패 %d: %s" % (status, payload))
    return payload["data"]["orderId"] if "orderId" in payload["data"] else payload["data"]["id"]


def wait_price_cached(product_id):
    """order-service 가 **이 상품의** product.updated 를 소비해 단가를 캐싱할 때까지 기다린다.

    안 기다리면 장바구니/주문이 ORD-009/ORD-007 로 죽고 그게 'saga 실패' 로 오인된다.
    조건은 반드시 **product_id 로 키잉**한다 — 초안은 group 이름만 보고 아무 행이나 있으면
    통과했는데, 앞 시나리오가 남긴 행이 그 조건을 만족시켜 **기다리지 않고 즉시 통과**했다
    (계획 P10 이 말한 시나리오 간 오염이 실제로 터진 지점이다)."""
    wait_for("order-service 단가 캐시 적재 product=%s" % product_id,
             lambda: query("order",
                           "SELECT product_id FROM product_price_cache WHERE product_id = %s",
                           (product_id,)),
             120)


def envelope_payload(raw):
    """`KafkaEventEnvelope` 의 payload 를 꺼낸다.

    필드 이름은 `data` 가 아니라 **`payload`** 다. 초안은 `.get("data", payload)` 로 조용히
    fallback 했는데, 그러면 키가 틀렸을 때 `reason=None` 이 나와 **계약 위반처럼 보인다** —
    실제로는 파서가 틀린 것이다. 없으면 그 자리에서 실패시킨다."""
    doc = json.loads(raw)
    if "payload" not in doc:
        raise AssertionError("envelope 에 payload 키가 없다 — 키 %r" % sorted(doc))
    return doc["payload"]


def nonzero(rows):
    """COUNT(*) 결과를 wait_for 조건으로 쓰기 위한 어댑터.

    COUNT 는 0 이어도 행 1개를 돌려주므로 truthy 다 — 그대로 wait_for 에 넘기면
    **기다리지 않고 즉시 통과**한다. 그 실수가 곧 vacuous-pass 다."""
    return rows if rows and rows[0]["c"] > 0 else None


def outbox_payload(service, event_type, aggregate_id):
    rows = query(service,
                 "SELECT payload, status FROM outbox_events "
                 "WHERE event_type = %s AND aggregate_id = %s ORDER BY id DESC LIMIT 1",
                 (event_type, str(aggregate_id)))
    return rows[0] if rows else None


# ----------------------------------------------------------------- 시나리오

def scenario_a():
    """A — 결제 실패 체인. 실패는 주입이 아니라 **실제 승인 실패**다:
    stub 의 confirm script 가 5xx 를 돌려주면 PaymentCommandService 의 catch 가
    payment.fail() + publishPaymentFailed() 를 같은 트랜잭션에서 수행한다."""
    sid = "a"
    user_id = 100
    initial_stock = 5
    product_id = create_product(sid, price=10000, stock=initial_stock)
    wait_price_cached(product_id)
    order_id = place_order(sid, user_id, product_id, 2)

    # 예약 성공까지 기다린다 — ensureConfirmable 이 예약 확정을 요구한다
    wait_for("예약 RESERVED/CONFIRMED order=%s" % order_id,
             lambda: query("product",
                           "SELECT status FROM stock_reservations WHERE order_id = %s "
                           "AND status IN ('RESERVED','CONFIRMED')", (order_id,)))
    # ready_for_payment 는 payment-service 가 stock.reservation.result 를 소비해야 선다.
    # 그 전에 승인을 부르면 ensureConfirmable 이 PAY-008 로 **가드 거부**하는데, 그건
    # 결제 실패가 아니다 — Toss 를 부르지도 않았으므로 payment.failed 도 발행되지 않는다.
    wait_for("payments 승인 준비 order=%s" % order_id,
             lambda: query("payment",
                 "SELECT id FROM payments WHERE order_id = %s AND ready_for_payment = 1 "
                 "AND status = 'PENDING'", (order_id,)))

    payment_key = "e2e-cfail-%s-%s" % (sid, order_id)
    status, body = http("payment", "POST", "/api/v1/payments/confirm",
                        {"paymentKey": payment_key, "orderId": order_id, "amount": 20000},
                        user_id=user_id)
    if status == 200:
        raise AssertionError("승인이 성공했다 — cfail script 가 먹지 않았다")
    # 가드 거부(PAY-008/009/010)와 PG 실패(PAY-005)를 구분한다. 구분하지 않으면 예약이
    # 확정되지 않아 거부된 것을 '결제 실패 체인 검증' 으로 착각한다.
    code = (body or {}).get("code")
    if code != "PAY-005":
        raise AssertionError("PG 실패가 아니라 가드 거부다 — code=%r body=%r" % (code, body))

    evidence = {}
    evidence["payment_status"] = wait_for(
        "payments FAILED", lambda: query("payment",
            "SELECT status FROM payments WHERE order_id = %s AND status = 'FAILED'", (order_id,))
    )[0]["status"]

    evidence["order_status"] = wait_for(
        "orders CANCELLED", lambda: query("order",
            "SELECT status FROM orders WHERE id = %s AND status = 'CANCELLED'", (order_id,))
    )[0]["status"]

    # 취소 사유는 orders 행이 아니라 order.cancelled payload 에 있다 (계획 V22)
    row = wait_for("order.cancelled outbox PUBLISHED",
                   lambda: (lambda r: r if r and r["status"] == "PUBLISHED" else None)(
                       outbox_payload("order", "order.cancelled", order_id)))
    reason = envelope_payload(row["payload"]).get("reason")
    if reason != "PAYMENT_FAILED":
        raise AssertionError("order.cancelled reason=%r (기대 PAYMENT_FAILED)" % reason)
    evidence["cancel_reason"] = reason
    evidence["order_cancelled_outbox"] = row["status"]

    evidence["reservation_status"] = wait_for(
        "예약 RELEASED", lambda: query("product",
            "SELECT status FROM stock_reservations WHERE order_id = %s AND status = 'RELEASED'",
            (order_id,))
    )[0]["status"]

    # 기준은 **초기 재고**다. 주문 직후에 읽으면 비동기 예약 차감의 전/후 어느 쪽인지
    # 불확정이라 기대값이 흔들린다. 불변식은 "복구 후 원래대로" 다.
    evidence["stock_restored"] = wait_for(
        "재고 원복 → %d" % initial_stock, lambda: query("product",
            "SELECT stock FROM inventories WHERE product_id = %s AND stock = %s",
            (product_id, initial_stock))
    )[0]["stock"]

    # payment.failed 소비자는 order/product/notification **3곳**이다.
    # Payment 는 자기 이벤트를 소비하지 않으므로 processed_events 행이 생기지 않는다 —
    # 그쪽 증적은 발행 outbox 가 PUBLISHED 인 것이다(계획 R3 #2).
    consumed = {}
    for svc, group in (("order", "order-svc-payment-failed-group"),
                       ("product", "product-svc-payment-failed-group"),
                       ("notification", "notification-svc-payment-failed-group")):
        consumed[svc] = wait_for(
            "%s processed_events(%s)" % (svc, group),
            lambda s=svc, g=group: nonzero(query(
                s, "SELECT COUNT(*) AS c FROM processed_events WHERE consumer_group = %s", (g,)))
        )[0]["c"]
    evidence["processed_events"] = consumed

    pay_outbox = outbox_payload("payment", "payment.failed", order_id)
    if not pay_outbox or pay_outbox["status"] != "PUBLISHED":
        raise AssertionError("payment.failed outbox 가 PUBLISHED 가 아니다: %r" % pay_outbox)
    evidence["payment_failed_outbox"] = pay_outbox["status"]

    evidence["notification_rows"] = wait_for(
        "notification 알림 행 user=%s" % user_id,
        lambda: nonzero(query("notification",
                              "SELECT COUNT(*) AS c FROM notifications WHERE user_id = %s",
                              (user_id,)))
    )[0]["c"]

    evidence["order_id"] = order_id
    return evidence


def scenario_b():
    """B — 예약 실패 체인 (부모 P12 명시 요구). 재고 부족을 주입해
    stock.reservation.result(success=false) → 주문 취소까지 본다."""
    sid = "b"
    user_id = 200
    product_id = create_product(sid, price=10000, stock=1)
    wait_price_cached(product_id)

    # 장바구니에 재고보다 많이 담는다 — 예약이 실패해야 하는 조건
    order_id = place_order(sid, user_id, product_id, 5)

    evidence = {"order_id": order_id}
    evidence["order_status"] = wait_for(
        "orders CANCELLED", lambda: query("order",
            "SELECT status FROM orders WHERE id = %s AND status = 'CANCELLED'", (order_id,))
    )[0]["status"]

    row = wait_for("order.cancelled outbox",
                   lambda: outbox_payload("order", "order.cancelled", order_id))
    reason = envelope_payload(row["payload"]).get("reason")
    if reason != "RESERVATION_FAILED":
        raise AssertionError("order.cancelled reason=%r (기대 RESERVATION_FAILED)" % reason)
    evidence["cancel_reason"] = reason

    held = query("product",
                 "SELECT COUNT(*) AS c FROM stock_reservations "
                 "WHERE order_id = %s AND status = 'RESERVED'", (order_id,))[0]["c"]
    if held != 0:
        raise AssertionError("예약 원장에 RESERVED 가 %d건 남았다" % held)
    evidence["reserved_remaining"] = held

    evidence["stock_intact"] = query(
        "product", "SELECT stock FROM inventories WHERE product_id = %s", (product_id,))[0]["stock"]
    return evidence


def publish(topic, key, payload):
    """요청 토픽에 직접 발행한다.

    시나리오 C 의 **트리거 감지**(Product marker · Order 보상 원장)는 "결제완료가 이미 취소된
    주문에 도착" 하는 경합 상태라 HTTP 로 결정적으로 만들 수 없다 — `ensureConfirmable` 이
    PENDING 이 아닌 결제의 승인을 거부한다(`Payment.java:181-192`). 그래서 C 는 **요청 토픽부터**
    시작한다. 그 앞 절반(감지 → 요청 발행이 같은 트랜잭션)은 ④-c-1b 통합테스트가 덮는다.
    이 축소는 계획 §9 에 미충족으로 적었다.
    """
    from kafka import KafkaProducer
    producer = KafkaProducer(bootstrap_servers="kafka:29092",
                             key_serializer=lambda k: k.encode("utf-8"),
                             value_serializer=lambda v: json.dumps(v).encode("utf-8"))
    try:
        producer.send(topic, key=key, value=payload).get(timeout=15)
    finally:
        producer.close()


def compensation_payload(order_id, reason, event_id):
    """KafkaEventEnvelope(eventId, eventType, timestamp, payload, schemaVersion) +
    CompensationRequestedPayload(orderId, reason, detectedAt).

    **금액을 싣지 않는다** — 환불 금액의 결정 주체는 Payment 다(ADR-0018 D1)."""
    return {
        "eventId": event_id,
        "eventType": "compensation.requested",
        "timestamp": "2026-08-27T12:00:00",
        "schemaVersion": 1,
        "payload": {
            "orderId": order_id,
            "reason": reason,
            "detectedAt": "2026-08-27T12:00:00",
        },
    }


def scenario_c():
    """C — 환불 체인 전구간. 요청 2경로를 **같은 주문에 동시 투입**해 fence 가 1행으로
    수렴하는지 보고, dispatcher → stub → payment.refunded → 3소비자 종결까지 확인한다."""
    sid = "c"
    order_id = 900000 + int(time.time()) % 90000
    payment_key = "e2e-ok-%s-%s" % (sid, order_id)

    with db("payment") as conn, conn.cursor() as cur:
        # payments 컬럼: order_id/user_id/payment_key/amount/status/ready_for_payment/
        # method/approved_at/created_at/version. updated_at 은 없다.
        cur.execute(
            "INSERT INTO payments (order_id, user_id, payment_key, amount, status,"
            " ready_for_payment, method, approved_at, created_at, version)"
            " VALUES (%s, 300, %s, 10000, 'APPROVED', 1, '간편결제', NOW(6), NOW(6), 0)",
            (order_id, payment_key))

    # 두 경로 동시 투입 — fence(order_id UNIQUE)가 1행으로 흡수해야 한다
    publish("stock.compensation.requested", str(order_id),
            compensation_payload(order_id, "PAID_BUT_UNRESERVED", "e2e-c-stock-%s" % order_id))
    publish("order.compensation.requested", str(order_id),
            compensation_payload(order_id, "PAID_BUT_CANCELLED", "e2e-c-order-%s" % order_id))

    evidence = {"order_id": order_id, "payment_key": payment_key}

    rows = wait_for("payment_refunds fence 1행",
                    lambda: query("payment",
                        "SELECT status, generation FROM payment_refunds WHERE order_id = %s",
                        (order_id,)))
    if len(rows) != 1:
        raise AssertionError("fence 위반 — payment_refunds %d행" % len(rows))
    evidence["refund_rows"] = len(rows)

    evidence["refund_status"] = wait_for(
        "환불 SUCCEEDED", lambda: query("payment",
            "SELECT status FROM payment_refunds WHERE order_id = %s AND status = 'SUCCEEDED'",
            (order_id,))
    )[0]["status"]

    evidence["payment_status"] = wait_for(
        "payments REFUNDED", lambda: query("payment",
            "SELECT status FROM payments WHERE order_id = %s AND status = 'REFUNDED'", (order_id,))
    )[0]["status"]

    row = wait_for("payment.refunded outbox PUBLISHED",
                   lambda: (lambda r: r if r and r["status"] == "PUBLISHED" else None)(
                       outbox_payload("payment", "payment.refunded", order_id)))
    evidence["payment_refunded_outbox"] = row["status"]

    # stub 호출 원장 — 성공 script 는 POST×1 이어야 한다(계획 P2 ledger 계약)
    calls = [c for c in stub_ledger() if c["paymentKey"] == payment_key]
    posts = [c for c in calls if c["method"] == "POST"]
    if len(posts) != 1:
        raise AssertionError("성공 script 인데 취소 POST 가 %d회 (기대 1)" % len(posts))
    if not posts[0]["idempotencyKey"]:
        raise AssertionError("Idempotency-Key 가 전송되지 않았다")
    evidence["stub_cancel_calls"] = len(posts)
    evidence["idempotency_key_sent"] = True
    return evidence


def scenario_d():
    """D — DLQ intake. 역직렬화 불가 레코드를 업무 토픽에 넣으면 .dlq 를 거쳐 원장 1행이 된다.

    **중복 판정은 재발행으로 만들 수 없다** — 재발행은 offset 이 달라져 다른 좌표가 되고
    UNIQUE 6컬럼이 새 행을 만든다(계획 V15). 여기서는 1행 + 6컬럼 non-null 까지만 본다.
    offset rewind 를 통한 attempt_count 증가는 §9 에 미충족으로 남긴다."""
    from kafka import KafkaProducer
    producer = KafkaProducer(bootstrap_servers="kafka:29092",
                             key_serializer=lambda k: k.encode("utf-8"),
                             value_serializer=lambda v: v)
    marker = "e2e-poison-%s" % RUN_ID
    try:
        producer.send("payment.failed", key=marker, value=b"{ this is not valid json").get(timeout=15)
    finally:
        producer.close()

    # origin_topic 은 DLT_ORIGINAL_TOPIC — **원본** 토픽이지 .dlq 가 아니다(DlqHeaders:42,56).
    rows = wait_for("dead_letter_records 적재",
                    lambda: query("order",
                        "SELECT cluster_id, topic_generation, origin_topic, origin_partition,"
                        " origin_offset, failed_consumer_group, status, attempt_count"
                        " FROM dead_letter_records WHERE origin_topic = 'payment.failed'"),
                    120)
    row = rows[0]
    for col in ("cluster_id", "topic_generation", "origin_topic", "origin_partition",
                "origin_offset", "failed_consumer_group"):
        if row[col] is None:
            raise AssertionError("식별자 %s 가 null — UNIQUE 가 poison record 를 여러 행으로 만든다" % col)
    return {
        "rows": len(rows),
        "status": row["status"],
        "attempt_count": row["attempt_count"],
        "failed_consumer_group": row["failed_consumer_group"],
    }


SCENARIOS = {"a": scenario_a, "b": scenario_b, "c": scenario_c, "d": scenario_d}


# ----------------------------------------------------------------- manifest

def write_manifest(scenario_id, result, evidence=None, error=None):
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, "manifest-%s.json" % scenario_id)
    doc = {
        "run_id": RUN_ID,
        "commit_sha": COMMIT_SHA,
        "scenario_id": scenario_id,
        "started_at": STARTED_AT,
        "finished_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "duration_seconds": round(time.time() - STARTED_MONO, 1),
        "result": result,
        # 매트릭스 expected 와 대조되는 부분은 evidence 안쪽뿐이다 — run_id 같은 메타를
        # 섞으면 exact equality 가 항상 실패한다(계획 P14).
        "evidence": evidence or {},
    }
    if error:
        doc["error"] = error
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2, sort_keys=True)
    return path


STARTED_AT = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
STARTED_MONO = time.time()


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd = argv[1]

    if cmd == "readiness":
        info = check_readiness()
        print(json.dumps(info, ensure_ascii=False, indent=2, sort_keys=True))
        return 0

    if cmd == "scenario":
        sid = argv[2]
        fn = SCENARIOS[sid]
        try:
            evidence = fn()
        except Exception as e:                      # noqa: BLE001 — 실패도 증적이다
            write_manifest(sid, "failure", error="%s: %s" % (type(e).__name__, e))
            print("시나리오 %s 실패 — %s" % (sid, e), file=sys.stderr)
            return 1
        path = write_manifest(sid, "success", evidence=evidence)
        print("시나리오 %s 통과 → %s" % (sid, path))
        print(json.dumps(evidence, ensure_ascii=False, indent=2, sort_keys=True))
        return 0

    print("알 수 없는 명령: %s" % cmd, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
