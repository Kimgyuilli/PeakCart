package com.peekcart.payment.presentation;

import com.peekcart.global.auth.CurrentUser;
import com.peekcart.global.auth.LoginUser;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.response.ApiResponse;
import com.peekcart.payment.application.PaymentCommandService;
import com.peekcart.payment.application.PaymentQueryService;
import com.peekcart.payment.application.WebhookService;
import com.peekcart.payment.application.dto.ConfirmPaymentCommand;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.presentation.dto.request.ConfirmPaymentRequest;
import com.peekcart.payment.presentation.dto.response.PaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

    @Value("${toss.payments.webhook-secret}")
    private String webhookSecret;

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
                PaymentResponse.from(paymentCommandService.confirmPayment(command))));
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
                PaymentResponse.from(paymentQueryService.getPaymentByOrderId(orderId))));
    }

    /**
     * Toss 웹훅을 수신한다.
     * HMAC-SHA256 서명 검증 후 멱등성 처리하여 저장한다.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = "Toss-Signature", required = false) String signature,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> payload
    ) {
        String rawPayload = payload.toString();
        verifySignature(signature, rawPayload);

        String paymentKey = String.valueOf(payload.getOrDefault("paymentKey", ""));
        String eventType = String.valueOf(payload.getOrDefault("eventType", ""));
        String idempKey = idempotencyKey != null ? idempotencyKey : paymentKey + "-" + eventType;

        webhookService.processWebhook(paymentKey, eventType, idempKey, rawPayload);
        return ResponseEntity.ok().build();
    }

    private void verifySignature(String signature, String payload) {
        if (signature == null) {
            throw new PaymentException(ErrorCode.PAY_006);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = Base64.getEncoder().encodeToString(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            if (!computed.equals(signature)) {
                throw new PaymentException(ErrorCode.PAY_006);
            }
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentException(ErrorCode.PAY_006);
        }
    }
}
