package com.peekcart.order.presentation;

import com.peekcart.global.auth.CurrentUser;
import com.peekcart.global.auth.LoginUser;
import com.peekcart.global.response.ApiResponse;
import com.peekcart.order.application.OrderCommandService;
import com.peekcart.order.application.OrderQueryService;
import com.peekcart.order.application.dto.CreateOrderCommand;
import com.peekcart.order.presentation.dto.request.CreateOrderRequest;
import com.peekcart.order.presentation.dto.response.OrderDetailResponse;
import com.peekcart.order.presentation.dto.response.OrderResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 주문 API 엔드포인트. 인증 필수.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderCommandService orderCommandService;
    private final OrderQueryService orderQueryService;

    @PostMapping
    public ResponseEntity<ApiResponse<OrderDetailResponse>> createOrder(
            @CurrentUser LoginUser loginUser,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        CreateOrderCommand command = new CreateOrderCommand(
                request.receiverName(), request.phone(), request.zipcode(), request.address());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(OrderDetailResponse.from(orderCommandService.createOrder(loginUser.userId(), command))));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<OrderResponse>>> getOrders(
            @CurrentUser LoginUser loginUser,
            Pageable pageable
    ) {
        Page<OrderResponse> page = orderQueryService.getOrders(loginUser.userId(), pageable)
                .map(OrderResponse::from);
        return ResponseEntity.ok(ApiResponse.of(page));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderDetailResponse>> getOrder(
            @CurrentUser LoginUser loginUser,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(ApiResponse.of(
                OrderDetailResponse.from(orderQueryService.getOrder(loginUser.userId(), id))));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<Void>> cancelOrder(
            @CurrentUser LoginUser loginUser,
            @PathVariable Long id
    ) {
        orderCommandService.cancelOrder(loginUser.userId(), id);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
