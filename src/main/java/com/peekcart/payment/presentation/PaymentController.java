package com.peekcart.payment.presentation;

import com.peekcart.global.auth.CurrentUser;
import com.peekcart.global.auth.LoginUser;
import com.peekcart.global.response.ApiResponse;
import com.peekcart.payment.application.PaymentCommandService;
import com.peekcart.payment.application.PaymentQueryService;
import com.peekcart.payment.application.WebhookService;
import com.peekcart.payment.application.dto.ConfirmPaymentCommand;
import com.peekcart.payment.presentation.dto.request.ConfirmPaymentRequest;
import com.peekcart.payment.presentation.dto.response.PaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 결제 API 컨트롤러.
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentCommandService paymentCommandService;
    private final PaymentQueryService paymentQueryService;
    private final WebhookService webhookService;

    /**
     * 결제를 승인한다.
     */
    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<PaymentResponse>> confirmPayment(
            @CurrentUser LoginUser loginUser,
            @Valid @RequestBody ConfirmPaymentRequest request
    ) {
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
                request.paymentKey(), request.orderId(), request.amount());
        return ResponseEntity.ok(ApiResponse.of(
                PaymentResponse.from(paymentCommandService.confirmPayment(loginUser.userId(), command))));
    }

    /**
     * 주문 ID로 결제 정보를 조회한다.
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(
            @CurrentUser LoginUser loginUser,
            @PathVariable Long orderId
    ) {
        return ResponseEntity.ok(ApiResponse.of(
                PaymentResponse.from(paymentQueryService.getPaymentByOrderId(loginUser.userId(), orderId))));
    }

    /**
     * Toss 웹훅을 수신한다.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = "Toss-Signature", required = false) String signature,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> payload
    ) {
        String rawPayload = payload.toString();
        String paymentKey = String.valueOf(payload.getOrDefault("paymentKey", ""));
        String eventType = String.valueOf(payload.getOrDefault("eventType", ""));
        String idempKey = idempotencyKey != null ? idempotencyKey : paymentKey + "-" + eventType;

        webhookService.processWebhook(signature, paymentKey, eventType, idempKey, rawPayload);
        return ResponseEntity.ok().build();
    }
}
