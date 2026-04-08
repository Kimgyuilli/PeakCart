/**
 * nGrinder Groovy Script — 상품 조회 TPS 시나리오
 *
 * Phase 3 Task 3-4 시나리오 1: 상품 조회 TPS (캐싱 전/후 비교)
 *
 * 측정 대상:
 *  - GET /api/v1/products?categoryId={1..5}&page={0..49}&size=20  (80% — 목록)
 *  - GET /api/v1/products/{id}  id ∈ [1, 1000]                    (20% — 상세)
 *
 * 실행 전 필수 환경변수 / Grinder 속성:
 *  - grinder.peekcart.baseUrl  (예: http://peekcart.peekcart.svc.cluster.local:8080
 *                                  또는 loadgen VM 에서 Internal LB / NodePort)
 *
 * 측정 절차 (캐싱 전/후 각각 동일 실행):
 *  1. warm-up: 1분 / 10 VUser
 *  2. 본측정: 5분 / 50 VUser (nGrinder controller UI 에서 설정)
 *  3. TPS · MTT · p99 수집
 *
 * 캐시 OFF baseline 과 캐시 ON 측정 사이에는 아래 절차를 수행:
 *  - kubectl set env deployment/peekcart PEEKCART_CACHE_ENABLED=false  (또는 true)
 *  - kubectl rollout restart deployment/peekcart
 *  - readiness 확인 후 warm-up 부터 재실행
 */
import net.grinder.script.GTest
import net.grinder.script.Grinder
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ngrinder.http.HTTPRequest
import org.ngrinder.http.HTTPRequestControl
import org.ngrinder.http.HTTPResponse

import static net.grinder.script.Grinder.grinder
import static org.junit.Assert.assertEquals

@RunWith(GrinderRunner)
class ProductQueryScenario {

    public static GTest testList
    public static GTest testDetail
    public static HTTPRequest request

    public static String baseUrl
    public static final Random random = new Random()

    @BeforeProcess
    public static void beforeProcess() {
        HTTPRequestControl.setConnectionTimeout(30000)
        testList = new GTest(1, "GET /api/v1/products (list)")
        testDetail = new GTest(2, "GET /api/v1/products/{id} (detail)")
        request = new HTTPRequest()

        baseUrl = System.getProperty("grinder.peekcart.baseUrl", "http://peekcart.peekcart.svc.cluster.local:8080")
        grinder.logger.info("peekcart baseUrl = {}", baseUrl)
    }

    @BeforeThread
    public void beforeThread() {
        testList.record(this, "queryList")
        testDetail.record(this, "queryDetail")
        grinder.statistics.delayReports = true
    }

    @Before
    public void before() {
    }

    @Test
    public void runScenario() {
        // 80% 목록, 20% 상세 — 통상 이커머스 조회 패턴 근사
        if (random.nextInt(100) < 80) {
            queryList()
        } else {
            queryDetail()
        }
    }

    public void queryList() {
        int categoryId = random.nextInt(5) + 1        // 1..5
        int page = random.nextInt(50)                  // 0..49 → 1,000 상품 / 20 size = 50 페이지
        String url = "${baseUrl}/api/v1/products?categoryId=${categoryId}&page=${page}&size=20"

        HTTPResponse response = request.GET(url)
        assertEquals(200, response.statusCode)
    }

    public void queryDetail() {
        int productId = random.nextInt(1000) + 1       // 1..1000
        String url = "${baseUrl}/api/v1/products/${productId}"

        HTTPResponse response = request.GET(url)
        assertEquals(200, response.statusCode)
    }
}
