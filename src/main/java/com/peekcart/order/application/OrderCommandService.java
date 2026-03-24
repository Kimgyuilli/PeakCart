package com.peekcart.order.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.dto.CreateOrderCommand;
import com.peekcart.order.application.dto.OrderDetailDto;
import com.peekcart.order.domain.event.OrderCancelledEvent;
import com.peekcart.order.domain.event.OrderCreatedEvent;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Cart;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderItemData;
import com.peekcart.order.domain.repository.CartRepository;
import com.peekcart.order.domain.repository.OrderRepository;
import com.peekcart.product.application.InventoryService;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 주문 생성/취소를 담당하는 애플리케이션 서비스.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class OrderCommandService {

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final InventoryService inventoryService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 장바구니를 기반으로 주문을 생성하고 재고를 즉시 차감한다.
     *
     * @throws OrderException 장바구니가 없으면 {@code ORD-006}, 비어있으면 {@code ORD-004}
     */
    public OrderDetailDto createOrder(Long userId, CreateOrderCommand command) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_006));

        if (cart.isEmpty()) {
            throw new OrderException(ErrorCode.ORD_004);
        }

        List<OrderItemData> itemDataList = cart.getItems().stream()
                .map(cartItem -> {
                    Product product = productRepository.findById(cartItem.getProductId())
                            .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
                    inventoryService.decreaseStock(cartItem.getProductId(), cartItem.getQuantity());
                    return new OrderItemData(product.getId(), cartItem.getQuantity(), product.getPrice());
                })
                .toList();

        Order order = Order.create(
                userId,
                generateOrderNumber(),
                command.receiverName(),
                command.phone(),
                command.zipcode(),
                command.address(),
                itemDataList
        );
        orderRepository.save(order);

        cart.clear();

        eventPublisher.publishEvent(
                new OrderCreatedEvent(order.getId(), userId, order.getOrderNumber(), order.getTotalAmount()));

        return OrderDetailDto.from(order);
    }

    /**
     * 주문을 취소하고 재고를 복구한다.
     *
     * @throws OrderException 주문이 없으면 {@code ORD-001}, 취소 불가 상태면 {@code ORD-003}
     */
    public void cancelOrder(Long userId, Long orderId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));

        order.cancel();

        for (var item : order.getOrderItems()) {
            inventoryService.restoreStock(item.getProductId(), item.getQuantity());
        }

        eventPublisher.publishEvent(
                new OrderCancelledEvent(order.getId(), userId, order.getOrderNumber()));
    }

    private String generateOrderNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "ORD-" + date + "-" + suffix;
    }
}
