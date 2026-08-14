package com.peekcart.order.infrastructure.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.dto.OrderCancelReason;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderItem;
import com.peekcart.support.ServiceTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ServiceTest
@DisplayName("OrderOutboxEventPublisher 단위 테스트 — MDC 캡처 계약")
class OrderOutboxEventPublisherTest {

    private OutboxEventRepository outboxEventRepository;
    private OrderOutboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        MDC.clear();
        outboxEventRepository = mock(OutboxEventRepository.class);
        publisher = new OrderOutboxEventPublisher(outboxEventRepository,
                new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("MDC 미설정 → 저장된 OutboxEvent 의 traceId / userId 둘 다 null")
    void mdcUnsetCapturesNulls() {
        publisher.publishOrderCancelled(stubOrder(), OrderCancelReason.USER_REQUESTED);

        OutboxEvent saved = captureSaved();
        assertThat(saved.getTraceId()).isNull();
        assertThat(saved.getUserId()).isNull();
    }

    @Test
    @DisplayName("traceId 만 설정 → traceId 영속, userId 는 null")
    void onlyTraceIdCaptured() {
        MDC.put("traceId", "trace-001");

        publisher.publishOrderCancelled(stubOrder(), OrderCancelReason.USER_REQUESTED);

        OutboxEvent saved = captureSaved();
        assertThat(saved.getTraceId()).isEqualTo("trace-001");
        assertThat(saved.getUserId()).isNull();
    }

    @Test
    @DisplayName("traceId / userId 둘 다 설정 → 둘 다 영속")
    void bothCaptured() {
        MDC.put("traceId", "trace-001");
        MDC.put("userId", "42");

        publisher.publishOrderCancelled(stubOrder(), OrderCancelReason.USER_REQUESTED);

        OutboxEvent saved = captureSaved();
        assertThat(saved.getTraceId()).isEqualTo("trace-001");
        assertThat(saved.getUserId()).isEqualTo("42");
    }

    @Test
    @DisplayName("취소 payload 에 복구 대상 items[](productId·quantity) 와 reason 이 실린다 (P5·P6, ADR-0012 D2)")
    void cancelledPayloadCarriesItemsAndReason() throws Exception {
        publisher.publishOrderCancelled(stubOrder(), OrderCancelReason.TIMEOUT);

        JsonNode payload = new ObjectMapper().readTree(captureSaved().getPayload()).get("payload");
        assertThat(payload.get("reason").asText()).isEqualTo("TIMEOUT");
        assertThat(payload.get("items")).hasSize(2);
        assertThat(payload.get("items").get(0).get("productId").asLong()).isEqualTo(11L);
        assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(payload.get("items").get(1).get("productId").asLong()).isEqualTo(22L);
        assertThat(payload.get("items").get(1).get("quantity").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("취소 사유는 진입점이 전달한 값 그대로 실린다 — 발행기가 추론하지 않는다 (P6)")
    void cancelledPayloadCarriesGivenReason() throws Exception {
        publisher.publishOrderCancelled(stubOrder(), OrderCancelReason.PAYMENT_FAILED);

        JsonNode payload = new ObjectMapper().readTree(captureSaved().getPayload()).get("payload");
        assertThat(payload.get("reason").asText()).isEqualTo("PAYMENT_FAILED");
    }

    private OutboxEvent captureSaved() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        return captor.getValue();
    }

    private static Order stubOrder() {
        // 스터빙 인자 안에서 mock() 을 호출하면 UnfinishedStubbingException 이므로 먼저 만든다
        List<OrderItem> items = List.of(stubItem(11L, 2, 1_000L), stubItem(22L, 3, 2_000L));
        Order order = mock(Order.class);
        given(order.getId()).willReturn(1L);
        given(order.getOrderNumber()).willReturn("ORD-001");
        given(order.getUserId()).willReturn(99L);
        given(order.getOrderItems()).willReturn(items);
        return order;
    }

    private static OrderItem stubItem(Long productId, int quantity, long unitPrice) {
        OrderItem item = mock(OrderItem.class);
        given(item.getProductId()).willReturn(productId);
        given(item.getQuantity()).willReturn(quantity);
        given(item.getUnitPrice()).willReturn(unitPrice);
        return item;
    }
}
