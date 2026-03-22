package com.peekcart.user.domain.model;

import com.peekcart.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    private User(String email, String passwordHash, String name) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = UserRole.USER;
    }

    public static User create(String email, String passwordHash, String name) {
        return new User(email, passwordHash, name);
    }

    public boolean matchesPassword(String rawPassword, PasswordEncoder encoder) {
        return encoder.matches(rawPassword, this.passwordHash);
    }

    public void updateProfile(String name) {
        this.name = name;
    }
}
