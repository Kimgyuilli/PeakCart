package com.peekcart.user.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.user.domain.UserException;
import com.peekcart.user.domain.UserRepository;
import com.peekcart.user.presentation.dto.request.UpdateProfileRequest;
import com.peekcart.user.presentation.dto.response.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class UserCommandService {

    private final UserRepository userRepository;

    public UserResponse updateMe(Long userId, UpdateProfileRequest request) {
        return userRepository.findById(userId)
                .map(user -> {
                    user.updateProfile(request.name());
                    return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getRole().name());
                })
                .orElseThrow(() -> new UserException(ErrorCode.USR_003));
    }
}
