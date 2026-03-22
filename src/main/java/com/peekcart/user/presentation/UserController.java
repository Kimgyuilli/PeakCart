package com.peekcart.user.presentation;

import com.peekcart.global.response.ApiResponse;
import com.peekcart.user.application.UserCommandService;
import com.peekcart.user.application.UserQueryService;
import com.peekcart.user.presentation.dto.UpdateProfileRequest;
import com.peekcart.user.presentation.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserQueryService userQueryService;
    private final UserCommandService userCommandService;

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserResponse>> getMe(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.of(userQueryService.getMe(userId)));
    }

    @PutMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserResponse>> updateMe(
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.of(userCommandService.updateMe(userId, request)));
    }
}
