package com.peekcart.payment.infrastructure.scheduler;

import com.peekcart.payment.application.PaymentRefundService;
import com.peekcart.payment.application.RefundProperties;
import com.peekcart.payment.infrastructure.toss.RefundExecutor;
import com.peekcart.global.port.SlackPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG 호출 표면 통제 (계획 P1 · [SAGA-P1-TOGGLE]).
 *
 * <p><b>기본값이 켜짐이어야 한다</b> — 프로퍼티를 잘못 두면 운영에서 환불이 조용히 멈춘다.
 * 그래서 "부재 시 등록" 을 명시적으로 고정한다.
 *
 * <p>키가 두 개인 이유: reconciliation 도 {@link RefundExecutor} 로 PG <b>조회</b>를 부르므로
 * dispatcher 하나만 꺼서는 외부 호출 표면이 닫히지 않는다.
 */
@DisplayName("[SAGA-P1-TOGGLE] 환불 스케줄러 활성 토글")
class RefundSchedulerToggleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // withBean() 은 빈 정의를 직접 등록해 @Conditional 을 **평가하지 않는다**.
            // withUserConfiguration() 은 AnnotatedBeanDefinitionReader 를 거쳐 조건을 평가한다 —
            // 이 차이를 놓치면 토글이 동작하지 않아도 테스트가 통과한다.
            .withUserConfiguration(StubBeans.class,
                    RefundDispatcher.class, RefundReconciliationScheduler.class);

    @Test
    @DisplayName("[SAGA-P1-TOGGLE] 프로퍼티 미지정이면 두 스케줄러가 모두 등록된다 — 운영 기본은 켜짐")
    void absentProperties_bothRegistered() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(RefundDispatcher.class);
            assertThat(context).hasSingleBean(RefundReconciliationScheduler.class);
        });
    }

    @Test
    @DisplayName("[SAGA-P1-TOGGLE] dispatch-enabled=false 면 dispatcher 만 빠지고 reconciliation 은 남는다")
    void dispatchDisabled_onlyDispatcherMissing() {
        runner.withPropertyValues("app.refund.dispatch-enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(RefundDispatcher.class);
            assertThat(context).hasSingleBean(RefundReconciliationScheduler.class);
        });
    }

    @Test
    @DisplayName("[SAGA-P1-TOGGLE] reconcile-enabled=false 면 reconciliation 만 빠진다")
    void reconcileDisabled_onlyReconcilerMissing() {
        runner.withPropertyValues("app.refund.reconcile-enabled=false").run(context -> {
            assertThat(context).hasSingleBean(RefundDispatcher.class);
            assertThat(context).doesNotHaveBean(RefundReconciliationScheduler.class);
        });
    }

    @Test
    @DisplayName("[SAGA-P1-TOGGLE] 둘 다 false 면 PG 를 부르는 빈이 하나도 남지 않는다 — E2E 가 쓰는 조합")
    void bothDisabled_noPgCallers() {
        runner.withPropertyValues(
                        "app.refund.dispatch-enabled=false",
                        "app.refund.reconcile-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RefundDispatcher.class);
                    assertThat(context).doesNotHaveBean(RefundReconciliationScheduler.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class StubBeans {
        @Bean PaymentRefundService refundService() { return Mockito.mock(PaymentRefundService.class); }
        @Bean RefundExecutor refundExecutor() { return Mockito.mock(RefundExecutor.class); }
        @Bean RefundProperties refundProperties() { return new RefundProperties(); }
        @Bean SlackPort slackPort() { return Mockito.mock(SlackPort.class); }
    }
}
