package com.peekcart.global.outbox;

import com.peekcart.notification.domain.model.Notification;
import com.peekcart.notification.domain.model.NotificationType;
import com.peekcart.notification.infrastructure.NotificationJpaRepository;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderItemData;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.infrastructure.outbox.OrderOutboxEventPublisher;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.infrastructure.outbox.PaymentOutboxEventPublisher;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.repository.InventoryRepository;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.user.domain.model.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "spring.task.scheduling.pool.size=1")
@Import(IntegrationTestConfig.class)
@DisplayName("Outbox → Kafka E2E 통합 테스트")
class OutboxKafkaIntegrationTest extends AbstractIntegrationTest {

    private Long userId;

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

    @Autowired OrderOutboxEventPublisher orderOutboxEventPublisher;
    @Autowired PaymentOutboxEventPublisher paymentOutboxEventPublisher;
    @Autowired OutboxPollingService outboxPollingService;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired NotificationJpaRepository notificationJpaRepository;

    private Long productId;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        User user = User.create("test@peekcart.com", "hashed-pw", "테스트유저");
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
    @DisplayName("order.created → Outbox 저장 → Kafka 발행 → Payment 생성 + 알림 생성")
    void orderCreated_e2e() {
        // given
        Order order = persistOrder(OrderStatus.PENDING);

        // when: Outbox에 이벤트 저장
        orderOutboxEventPublisher.publishOrderCreated(order);
        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(100);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getStatus()).isEqualTo(OutboxEventStatus.PENDING);

        // when: 스케줄러 수동 호출 → Kafka 발행
        outboxPollingService.pollAndPublish();

        // then: OutboxEvent PUBLISHED 전이
        List<OutboxEvent> afterPublish = outboxEventRepository.findPendingEvents(100);
        assertThat(afterPublish).isEmpty();

        // then: Consumer 처리 대기 → Payment(PENDING) 생성 + Notification(ORDER_CREATED) 생성
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Optional<Payment> payment = paymentRepository.findByOrderId(order.getId());
            assertThat(payment).isPresent();
            assertThat(payment.get().getStatus().name()).isEqualTo("PENDING");

            List<Notification> notifications = notificationJpaRepository
                    .findByUserId(userId, PageRequest.of(0, 10)).getContent();
            assertThat(notifications).anyMatch(n -> n.getType() == NotificationType.ORDER_CREATED);
        });
    }

    @Test
    @DisplayName("payment.completed → 주문 상태 PAYMENT_COMPLETED + 알림 생성")
    void paymentCompleted_e2e() {
        // given
        Order order = persistOrder(OrderStatus.PAYMENT_REQUESTED);
        Payment payment = persistPayment(order.getId());

        // when
        paymentOutboxEventPublisher.publishPaymentCompleted(payment, userId);
        outboxPollingService.pollAndPublish();

        // then
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            EntityManager em = emf.createEntityManager();
            try {
                Order updated = em.find(Order.class, order.getId());
                assertThat(updated.getStatus()).isEqualTo(OrderStatus.PAYMENT_COMPLETED);
            } finally {
                em.close();
            }

            List<Notification> notifications = notificationJpaRepository
                    .findByUserId(userId, PageRequest.of(0, 10)).getContent();
            assertThat(notifications).anyMatch(n -> n.getType() == NotificationType.PAYMENT_COMPLETED);
        });
    }

    @Test
    @DisplayName("payment.failed → 주문 취소 + 재고 복구 + 알림 생성")
    void paymentFailed_e2e() {
        // given: 주문(PAYMENT_REQUESTED) + OrderItem(productId, quantity=2), 재고 미리 차감
        Order order = persistOrderWithQuantity(2);
        decreaseStock(productId, 2);
        Payment payment = persistPayment(order.getId());

        // when
        paymentOutboxEventPublisher.publishPaymentFailed(payment, userId);
        outboxPollingService.pollAndPublish();

        // then
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            EntityManager em = emf.createEntityManager();
            try {
                Order updated = em.find(Order.class, order.getId());
                assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            } finally {
                em.close();
            }

            Inventory inventory = inventoryRepository.findByProductId(productId)
                    .orElseThrow();
            assertThat(inventory.getStock()).isEqualTo(100);

            List<Notification> notifications = notificationJpaRepository
                    .findByUserId(userId, PageRequest.of(0, 10)).getContent();
            assertThat(notifications).anyMatch(n -> n.getType() == NotificationType.PAYMENT_FAILED);
        });
    }

    @Test
    @DisplayName("order.cancelled → NotificationConsumer만 소비 (알림 생성)")
    void orderCancelled_e2e() {
        // given: 실제 플로우처럼 주문 취소 후 이벤트 발행
        Order order = persistOrder(OrderStatus.PENDING);
        cancelOrder(order.getId());

        // when
        orderOutboxEventPublisher.publishOrderCancelled(order);
        outboxPollingService.pollAndPublish();

        // then
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Notification> notifications = notificationJpaRepository
                    .findByUserId(userId, PageRequest.of(0, 10)).getContent();
            assertThat(notifications).anyMatch(n -> n.getType() == NotificationType.ORDER_CANCELLED);
        });

        // Payment가 생성되지 않았는지 확인
        Optional<Payment> payment = paymentRepository.findByOrderId(order.getId());
        assertThat(payment).isEmpty();
    }

    @Test
    @DisplayName("PUBLISHED 이벤트는 재폴링 시 중복 발행되지 않는다")
    void publishedEvent_notRePublished() {
        // given
        Order order = persistOrder(OrderStatus.PENDING);
        orderOutboxEventPublisher.publishOrderCancelled(order);
        outboxPollingService.pollAndPublish();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Notification> notifications = notificationJpaRepository
                    .findByUserId(userId, PageRequest.of(0, 10)).getContent();
            assertThat(notifications).hasSize(1);
        });

        // when: 재폴링
        outboxPollingService.pollAndPublish();

        // then: PENDING 이벤트 없음 + Notification 수 변화 없음
        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(100);
        assertThat(pending).isEmpty();

        List<Notification> notifications = notificationJpaRepository
                .findByUserId(userId, PageRequest.of(0, 10)).getContent();
        assertThat(notifications).hasSize(1);
    }

    // ── 헬퍼 메서드 ──

    private Order persistOrder(OrderStatus targetStatus) {
        return persistOrderWithQuantity(targetStatus, 2);
    }

    private Order persistOrderWithQuantity(int quantity) {
        return persistOrderWithQuantity(OrderStatus.PAYMENT_REQUESTED, quantity);
    }

    private Order persistOrderWithQuantity(OrderStatus targetStatus, int quantity) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        List<OrderItemData> items = List.of(new OrderItemData(productId, quantity, 50_000L));
        Order order = Order.create(userId, "ORD-TEST-" + System.nanoTime(),
                "홍길동", "010-1234-5678", "12345", "서울시 강남구", items);

        if (targetStatus == OrderStatus.PAYMENT_REQUESTED) {
            order.transitionTo(OrderStatus.PAYMENT_REQUESTED);
        }

        em.persist(order);
        em.getTransaction().commit();
        em.close();
        return order;
    }

    private void cancelOrder(Long orderId) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Order order = em.find(Order.class, orderId);
        order.cancel();
        em.getTransaction().commit();
        em.close();
    }

    private void decreaseStock(Long productId, int quantity) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Inventory inv = em.createQuery(
                        "SELECT i FROM Inventory i WHERE i.product.id = :pid", Inventory.class)
                .setParameter("pid", productId)
                .getSingleResult();
        inv.decrease(quantity);
        em.getTransaction().commit();
        em.close();
    }

    private Payment persistPayment(Long orderId) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        Payment payment = Payment.create(orderId, 100_000L);
        em.persist(payment);
        em.getTransaction().commit();
        em.close();
        return payment;
    }
}
