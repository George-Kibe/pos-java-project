package com.pos.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.auth.domain.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Always look up by the normalized form; the raw address is for display only. */
    Optional<User> findByEmailNormalized(String emailNormalized);

    boolean existsByEmailNormalized(String emailNormalized);

    /**
     * Name or email contains {@code query}.
     *
     * <p>Deliberately no {@code :query IS NULL OR ...} branch. PostgreSQL receives a null string
     * parameter as untyped and fails the whole statement with "function lower(bytea) does not
     * exist"; the caller picks {@link #findAll(Pageable)} instead when there is nothing to search
     * for, which also produces a simpler plan.
     */
    @Query(
            """
            SELECT u FROM User u
            WHERE LOWER(u.fullName) LIKE LOWER(CONCAT('%', :query, '%'))
               OR u.emailNormalized LIKE LOWER(CONCAT('%', :query, '%'))
            """)
    Page<User> search(@Param("query") String query, Pageable pageable);

    long countByStatus(com.pos.auth.domain.UserStatus status);

    /** Everyone who could approve at a lane; the caller narrows by permission and branch. */
    List<User> findByStatusAndPinHashIsNotNull(com.pos.auth.domain.UserStatus status);

    /**
     * Invalidates outstanding access tokens for everyone holding a role.
     *
     * <p>Necessary because permissions are baked into the token. Without this, removing {@code
     * sale:void} from SUPERVISOR would leave every supervisor still able to void sales for the
     * remaining life of the token they are already carrying.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            UPDATE User u SET u.tokenVersion = u.tokenVersion + 1
            WHERE u.id IN (SELECT holder.id FROM User holder JOIN holder.roles r WHERE r.id = :roleId)
            """)
    int bumpTokenVersionForRole(@Param("roleId") UUID roleId);

    /** Current token versions of every holder of a role, so each can be published to the cache. */
    @Query(
            "SELECT u.id AS id, u.tokenVersion AS tokenVersion FROM User u JOIN u.roles r WHERE r.id = :roleId")
    List<TokenVersionView> findTokenVersionsByRole(@Param("roleId") UUID roleId);

    /** Projection: just the two fields the token-version cache needs. */
    interface TokenVersionView {
        UUID getId();

        int getTokenVersion();
    }
}
