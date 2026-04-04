package com.peekcart.global.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.OutboxPollingService;
import com.peekcart.global.outbox.dto.KafkaEventEnvelope;
import com.peekcart.global.outbox.dto.OrderCreatedPayload;
import com.peekcart.global.outbox.dto.OrderItemPayload;
import com.peekcart.global.port.SlackPort;
import com.peekcart.notification.domain.model.Notification;
import com.peekcart.notification.domain.model.NotificationType;
import com.peekcart.notification.infrastructure.NotificationJpaRepository;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderItemData;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.infrastructure.outbox.OrderOutboxEventPublisher;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "spring.task.scheduling.pool.size=1")
@Import(IdempotencyIntegrationTest.TestConfig.class)
@DisplayName("Consumer 멱등성 통합 테스트")
class IdempotencyIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired EntityManagerFactory emf;
    @Autowired OrderOutboxEventPublisher orderOutboxEventPublisher;
    @Autowired OutboxPollingService outboxPollingService;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired NotificationJpaRepository notificationJpaRepository;
    @Autowired ProcessedEventJpaRepository processedEventJpaRepository;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;

    private Long userId;
    private Long productId;

    @TestConfiguration
    static class TestConfig {
        @Bean
        SlackPort slackPort() {
            return message -> {};
        }
    }

    @BeforeEach
    void setUp() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        em.createNativeQuery("DELETE FROM outbox_events").executeUpdate();
        em.createNativeQuery("DELETE FROM processed_events").executeUpdate();
        em.createNativeQuery("DELETE FROM notifications").executeUpdate();
        em.createNativeQuery("DELETE FROM webhook_logs").executeUpdate();
        em.createNativeQuery("DELETE FROM payments").executeUpdate();
        em.createNativeQuery("DELETE FROM order_items").executeUpdate();
        em.createNativeQuery("DELETE FROM orders").executeUpdate();
        em.createNativeQuery("DELETE FROM cart_items").executeUpdate();
        em.createNativeQuery("DELETE FROM carts").executeUpdate();
        em.createNativeQuery("DELETE FROM inventories").executeUpdate();
        em.createNativeQuery("DELETE FROM products").executeUpdate();
        em.createNativeQuery("DELETE FROM categories").executeUpdate();
        em.createNativeQuery("DELETE FROM refresh_tokens").executeUpdate();
        em.createNativeQuery("DELETE FROM addresses").executeUpdate();
        em.createNativeQuery("DELETE FROM users").executeUpdate();

        var user = com.peekcart.user.domain.model.User.create("test@peekcart.com", "hashed-pw", "테스트유저");
        em.persist(user);
        em.flush();
        userId = user.getId();

        Category category = Category.create("테스트 카테고리", null);
        em.persist(category);
        em.flush();

        Product product = Product.create(category, "테스트 상품", "설명", 50_000L, null);
        em.persist(product);
        em.flush();
        productId = product.getId();

        Inventory inventory = Inventory.create(product, 100);
        em.persist(inventory);

        em.getTransaction().commit();
        em.close();
    }

    @Test
    @DisplayName("동일 이벤트를 같은 consumer group에서 2회 소비하면 1회만 처리된다")
    void duplicateEvent_sameConsumerGroup_processedOnce() {
        // given: order.created 이벤트 발행 → Consumer 처리 대기
        Order order = persistOrder();
        orderOutboxEventPublisher.publishOrderCreated(order);

        // Outbox에서 eventId 추출
        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(100);
        assertThat(pending).hasSize(1);
        String eventId = pending.get(0).getEventId();
        String payload = pending.get(0).getPayload();

        // Kafka로 발행 + Consumer 처리 대기
        outboxPollingService.pollAndPublish();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(paymentRepository.findByOrderId(order.getId())).isPresent();
        });

        // 처리 결과 기록
        long paymentCountBefore = countPaymentsByOrderId(order.getId());
        long notificationCountBefore = countNotifications();

        // when: 동일 eventId 메시지를 KafkaTemplate으로 직접 재전송
        kafkaTemplate.send("order.created", order.getId().toString(), payload);

        // then: 충분히 대기 후에도 Payment/Notification 수 변화 없음
        await().during(3, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(countPaymentsByOrderId(order.getId())).isEqualTo(paymentCountBefore);
            assertThat(countNotifications()).isEqualTo(notificationCountBefore);
        });
    }

    @Test
    @DisplayName("동일 이벤트를 다른 consumer group에서 각각 독립 처리한다")
    void sameEvent_differentConsumerGroups_processedIndependently() {
        // given: order.created → PaymentEventConsumer + NotificationConsumer 모두 소비
        Order order = persistOrder();
        orderOutboxEventPublisher.publishOrderCreated(order);

        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(100);
        String eventId = pending.get(0).getEventId();

        // when
        outboxPollingService.pollAndPublish();

        // then: 두 consumer group 모두 처리 완료
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(paymentRepository.findByOrderId(order.getId())).isPresent();

            List<Notification> notifications = notificationJpaRepository
                    .findByUserId(userId, org.springframework.data.domain.PageRequest.of(0, 10))
                    .getContent();
            assertThat(notifications).anyMatch(n -> n.getType() == NotificationType.ORDER_CREATED);
        });

        // processed_events에 같은 eventId로 2건 존재 (서로 다른 consumer group)
        List<ProcessedEvent> processedEvents = processedEventJpaRepository.findAll().stream()
                .filter(pe -> pe.getEventId().equals(eventId))
                .toList();
        assertThat(processedEvents).hasSize(2);
        assertThat(processedEvents)
                .extracting(ProcessedEvent::getConsumerGroup)
                .containsExactlyInAnyOrder(
                        "payment-svc-order-created-group",
                        "notification-svc-order-created-group"
                );
    }

    // ── 헬퍼 메서드 ──

    private Order persistOrder() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        List<OrderItemData> items = List.of(new OrderItemData(productId, 2, 50_000L));
        Order order = Order.create(userId, "ORD-TEST-" + System.nanoTime(),
                "홍길동", "010-1234-5678", "12345", "서울시 강남구", items);
        order.transitionTo(OrderStatus.PAYMENT_REQUESTED);

        em.persist(order);
        em.getTransaction().commit();
        em.close();
        return order;
    }

    private long countPaymentsByOrderId(Long orderId) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery(
                            "SELECT COUNT(p) FROM Payment p WHERE p.orderId = :orderId", Long.class)
                    .setParameter("orderId", orderId)
                    .getSingleResult();
        } finally {
            em.close();
        }
    }

    private long countNotifications() {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery("SELECT COUNT(n) FROM Notification n", Long.class)
                    .getSingleResult();
        } finally {
            em.close();
        }
    }
}
