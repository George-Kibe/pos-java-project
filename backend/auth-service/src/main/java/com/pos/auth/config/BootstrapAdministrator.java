package com.pos.auth.config;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.domain.Branch;
import com.pos.auth.domain.Role;
import com.pos.auth.domain.User;
import com.pos.auth.domain.UserStatus;
import com.pos.auth.repository.BranchRepository;
import com.pos.auth.repository.RoleRepository;
import com.pos.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Creates a first administrator when the users table is empty, so a fresh deployment is reachable.
 *
 * <p>Only ever runs against an empty table, which is what stops it resurrecting an account that an
 * administrator deliberately deactivated. The password comes from configuration and is never
 * defaulted: a well-known default administrator password is how systems get breached on day one.
 * The account is flagged to force a password change at first sign-in.
 */
@Component
@RequiredArgsConstructor
public class BootstrapAdministrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdministrator.class);

    private static final String SUPER_ADMIN = "SUPER_ADMIN";

    private final UserRepository users;
    private final RoleRepository roles;
    private final BranchRepository branches;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AuthProperties.Bootstrap config = properties.getBootstrap();
        if (!config.isEnabled()) {
            return;
        }

        if (users.count() > 0) {
            // Not an empty system; nothing to bootstrap.
            return;
        }

        if (config.getEmail().isBlank() || config.getPassword().isBlank()) {
            log.warn(
                    "No users exist and pos.auth.bootstrap.email/password are not set, so no"
                            + " administrator was created. Set both and restart, or this deployment"
                            + " cannot be administered.");
            return;
        }

        Optional<Role> superAdmin = roles.findByCode(SUPER_ADMIN);
        if (superAdmin.isEmpty()) {
            log.error(
                    "Role {} is missing; migrations may not have run. No administrator created.",
                    SUPER_ADMIN);
            return;
        }

        User admin = new User();
        admin.setEmail(config.getEmail().trim());
        admin.setEmailNormalized(User.normalizeEmail(config.getEmail()));
        admin.setPasswordHash(passwordEncoder.encode(config.getPassword()));
        admin.setFullName("System Administrator");
        admin.setStatus(UserStatus.ACTIVE);
        admin.setMustChangePassword(true);
        admin.setRoles(new HashSet<>(List.of(superAdmin.get())));
        admin.setBranches(new HashSet<>(branches.findAll()));
        users.save(admin);

        log.warn(
                "Created bootstrap administrator {} with SUPER_ADMIN. It must change its password"
                        + " at first sign-in. Remove pos.auth.bootstrap.password from configuration"
                        + " once that is done.",
                admin.getEmail());
    }

    /** Exposed for the integration test, which asserts the branch assignment. */
    List<Branch> allBranches() {
        return branches.findAll();
    }
}
