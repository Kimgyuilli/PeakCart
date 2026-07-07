package com.peekcart.user.infrastructure;

import com.peekcart.user.domain.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * {@link RefreshToken} 엔티티에 대한 Spring Data JPA 리포지터리.
 * <p>상태전이는 조건부 벌크 UPDATE 로 원자성을 보장한다(ADR-0013 D4).
 */
public interface RefreshTokenJpaRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.status = com.peekcart.user.domain.model.RefreshTokenStatus.ROTATED,
                   t.rotatedAt = :rotatedAt,
                   t.graceUntil = :graceUntil,
                   t.replacedByTokenId = :newTokenId
             where t.tokenHash = :tokenHash
               and t.status = com.peekcart.user.domain.model.RefreshTokenStatus.ACTIVE
            """)
    int rotateActive(@Param("tokenHash") String tokenHash,
                     @Param("newTokenId") Long newTokenId,
                     @Param("rotatedAt") LocalDateTime rotatedAt,
                     @Param("graceUntil") LocalDateTime graceUntil);

    @Modifying(clearAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.graceUntil = :now
             where t.tokenHash = :tokenHash
               and t.status = com.peekcart.user.domain.model.RefreshTokenStatus.ROTATED
               and t.graceUntil > :now
            """)
    int consumeGraceOnce(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.status = com.peekcart.user.domain.model.RefreshTokenStatus.ROTATED,
                   t.rotatedAt = :rotatedAt,
                   t.graceUntil = null,
                   t.replacedByTokenId = :newTokenId
             where t.id = :tokenId
               and t.status = com.peekcart.user.domain.model.RefreshTokenStatus.ACTIVE
            """)
    int forceRotate(@Param("tokenId") Long tokenId,
                    @Param("newTokenId") Long newTokenId,
                    @Param("rotatedAt") LocalDateTime rotatedAt);

    @Modifying(clearAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.status = com.peekcart.user.domain.model.RefreshTokenStatus.REVOKED
             where t.familyId = :familyId
               and t.status <> com.peekcart.user.domain.model.RefreshTokenStatus.REVOKED
            """)
    void revokeFamily(@Param("familyId") String familyId);

    @Modifying(clearAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.status = com.peekcart.user.domain.model.RefreshTokenStatus.REVOKED
             where t.userId = :userId
               and t.status <> com.peekcart.user.domain.model.RefreshTokenStatus.REVOKED
            """)
    void revokeAllByUserId(@Param("userId") Long userId);
}
