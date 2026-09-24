package com.pos.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Policy knobs for registration, lockout and bootstrapping. */
@ConfigurationProperties(prefix = "pos.auth")
@Getter
@Setter
public class AuthProperties {

    private final Otp otp = new Otp();
    private final Lockout lockout = new Lockout();
    private final PasswordReset passwordReset = new PasswordReset();
    private final Bootstrap bootstrap = new Bootstrap();
    private final Approval approval = new Approval();

    /**
     * Role granted to a self-registered user once verified. Empty by default: a new account should
     * be able to do nothing at all until an administrator assigns it a role.
     */
    private String defaultRole = "";

    /** Supervisor approvals at the lane. */
    @Getter
    @Setter
    public static class Approval {
        /** How long an approval token lives: long enough to finish the action, no longer. */
        private Duration ttl = Duration.ofMinutes(2);

        /** Wrong PINs before the PIN locks. */
        private int maxFailures = 5;

        private Duration lockDuration = Duration.ofMinutes(15);

        /** The permissions a PIN can approve at a lane. Anything else needs a real sign-in. */
        private java.util.List<String> permissions =
                new java.util.ArrayList<>(
                        java.util.List.of(
                                "price:override",
                                "sale:void",
                                "sale:refund",
                                "cash:drop",
                                "cash:intraday"));
    }

    @Getter
    @Setter
    public static class Otp {
        private int length = 6;

        /** Short enough that a six-digit code cannot be worked through in time. */
        private Duration ttl = Duration.ofMinutes(10);

        private int maxAttempts = 5;

        /** Stops the endpoint being used to send mail at someone repeatedly. */
        private Duration resendCooldown = Duration.ofSeconds(60);
    }

    @Getter
    @Setter
    public static class Lockout {
        /** Failures within the window before the account locks. */
        private int maxFailures = 5;

        private Duration window = Duration.ofMinutes(15);

        /** First lock duration; doubles with each subsequent lock, up to the maximum. */
        private Duration baseDuration = Duration.ofMinutes(1);

        private Duration maxDuration = Duration.ofHours(1);
    }

    @Getter
    @Setter
    public static class PasswordReset {
        private Duration ttl = Duration.ofMinutes(30);
    }

    @Getter
    @Setter
    public static class Bootstrap {
        /** Creates a first SUPER_ADMIN when the users table is empty. */
        private boolean enabled = true;

        private String email = "";

        private String password = "";
    }
}
