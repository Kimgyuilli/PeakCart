package com.peekcart.user.domain.repository;

import com.peekcart.user.domain.model.User;

import java.util.Optional;

public interface UserRepository {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    User save(User user);
    Optional<User> findById(Long id);
}
