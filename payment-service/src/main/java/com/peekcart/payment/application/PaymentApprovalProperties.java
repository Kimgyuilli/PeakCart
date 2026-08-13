package com.peekcart.payment.application;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 결제 승인 정책 (계획 ④-a GW-2 #1). 동작 정책이므로 base {@code application.yml} 소유(ADR-0007).
 *
 * <p>{@code leaseApprovalMargin} 은 PG 승인에 허용하는 최대 소요 시간이다. 남은 예약 lease 가 이보다
 * 짧으면 승인을 <b>시작하지 않는다</b> — 승인 도중 lease 가 만료되면 Order 의 만료 취소가 재고를
 * 복구·재판매한 뒤 과금이 성립할 수 있기 때문이다.
 *
 * <p><b>한계</b>: 이는 경합 창을 마진 이내로 줄이는 조치이지 fence 가 아니다. PG 호출이 마진을 초과하면
 * 창은 다시 열린다. 근본 해결(예약을 승인 전용 상태로 CAS 전이) 은 별도 ADR 대상이다(계획 §2.6 R-1).
 */
@ConfigurationProperties(prefix = "app.payment")
@Validated
@Getter
@Setter
public class PaymentApprovalProperties {

    /** PG 승인 최대 소요 예상 시간. 남은 lease 가 이보다 짧으면 PAY-010 으로 거부한다. */
    @NotNull
    private Duration leaseApprovalMargin;
}
