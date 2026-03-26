package com.peekcart.notification.presentation;

import com.peekcart.global.auth.CurrentUser;
import com.peekcart.global.auth.LoginUser;
import com.peekcart.global.response.ApiResponse;
import com.peekcart.notification.application.NotificationQueryService;
import com.peekcart.notification.presentation.dto.response.NotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 API 컨트롤러.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    @GetMapping
    public ResponseEntity<ApiResponse<Page<NotificationResponse>>> getNotifications(
            @CurrentUser LoginUser loginUser,
            Pageable pageable
    ) {
        Page<NotificationResponse> page = notificationQueryService.getNotifications(loginUser.userId(), pageable)
                .map(NotificationResponse::from);
        return ResponseEntity.ok(ApiResponse.of(page));
    }
}
