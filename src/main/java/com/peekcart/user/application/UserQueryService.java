package com.peekcart.user.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.user.domain.exception.UserException;
import com.peekcart.user.domain.repository.UserRepository;
import com.peekcart.user.presentation.dto.response.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserQueryService {

    private final UserRepository userRepository;

    public UserResponse getMe(Long userId) {
        return userRepository.findById(userId)
                .map(user -> new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getRole().name()))
                .orElseThrow(() -> new UserException(ErrorCode.USR_003));
    }
}
