package com.pos.auth.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.domain.RefreshToken;
import com.pos.auth.domain.User;
import com.pos.auth.repository.RefreshTokenRepository;
import com.pos.auth.repository.UserRepository;
import com.pos.auth.security.AccessTokenIssuer;
import com.pos.auth.security.JwtProperties;
import com.pos.auth.security.SecureTokens;
import com.pos.common.error.Errors;

/** Login, refresh-token rotation and logout. */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private static final String REASON_UNKNOWN_ACCOUNT = "unknown_account";
    private static final String REASON_BAD_PASSWORD = "bad_password";
    private static final String REASON_LOCKED = "account_locked";
    private static final String REASON_NOT_ACTIVE = "account_not_active";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AccessTokenIssuer accessTokenIssuer;
    private final LoginAttemptService loginAttempts;
    private final SessionRevocationService sessionRevocation;
    private final AuditService audit;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;

    /**
     * A valid-looking hash to verify against when the account does not exist.
     *
     * <p>Without this, an unknown address returns in microseconds while a known one takes as long
     * as Argon2 needs, and the difference is measurable over the network. Doing the same work
     * either way is what makes the endpoint quiet about which addresses are registered.
     */
    private final String dummyHash;

    public AuthenticationService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            AccessTokenIssuer accessTokenIssuer,
            LoginAttemptService loginAttempts,
            SessionRevocationService sessionRevocation,
            AuditService audit,
            PasswordEncoder passwordEncoder,
            JwtProperties jwtProperties) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.accessTokenIssuer = accessTokenIssuer;
        this.loginAttempts = loginAttempts;
        this.sessionRevocation = sessionRevocation;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
        this.jwtProperties = jwtProperties;
        this.dummyHash = passwordEncoder.encode("a-password-that-is-never-correct");
    }

    @Transactional
    public TokenPair login(String email, String rawPassword, String ip, String userAgent) {
        String normalized = User.normalizeEmail(email);
        Optional<User> found = users.findByEmailNormalized(normalized);

        if (found.isEmpty()) {
            // Burn the same time a real check would take, then fail identically.
            passwordEncoder.matches(rawPassword, dummyHash);
            loginAttempts.recordFailure(null, normalized, ip, userAgent, REASON_UNKNOWN_ACCOUNT);
            throw invalidCredentials();
        }

        User user = found.get();

        if (user.isLocked()) {
            loginAttempts.recordFailure(user.getId(), normalized, ip, userAgent, REASON_LOCKED);
            // Says it is locked rather than pretending the password is wrong: a locked-out cashier
            // needs to know to fetch a supervisor, not to keep guessing.
            throw new Errors.UnauthorizedException(
                    "auth.account_locked",
                    "This account is temporarily locked after repeated failed attempts.");
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            loginAttempts.recordFailure(
                    user.getId(), normalized, ip, userAgent, REASON_BAD_PASSWORD);
            throw invalidCredentials();
        }

        if (!user.canAuthenticate()) {
            loginAttempts.recordFailure(user.getId(), normalized, ip, userAgent, REASON_NOT_ACTIVE);
            // Checked after the password so that an unverified account is not distinguishable
            // from a wrong password to someone who does not know the password.
            throw new Errors.UnauthorizedException(
                    "auth.not_active",
                    "This account is not active. Verify your email or contact an administrator.");
        }

        loginAttempts.recordSuccess(user, normalized, ip, userAgent);
        return issuePair(user, UUID.randomUUID(), ip, userAgent);
    }

    /**
     * Exchanges a refresh token for a new pair, rotating it.
     *
     * <p>Rotation with reuse detection is what makes a stolen refresh token survivable. Each token
     * works once. If one is presented twice, either it was copied and the thief is now using it, or
     * the legitimate client is retrying after the response was lost - and there is no way to tell
     * which. So the whole family is revoked, both parties are forced to log in again, and the theft
     * ends there rather than granting indefinite access.
     */
    @Transactional
    public TokenPair refresh(String presentedToken, String ip, String userAgent) {
        String hash = SecureTokens.hash(presentedToken);
        RefreshToken stored =
                refreshTokens
                        .findByTokenHash(hash)
                        .orElseThrow(AuthenticationService::invalidToken);

        if (stored.isUsed()) {
            // Separate transaction: this call ends in a 401, and a revocation rolled back with it
            // would leave the stolen token working.
            sessionRevocation.revokeFamily(stored.getFamilyId(), "reuse_detected");
            log.warn(
                    "Refresh token reuse detected for family {}; family revoked",
                    stored.getFamilyId());
            audit.recordFor(
                    stored.getUserId(),
                    null,
                    AuditService.REFRESH_TOKEN_REUSE_DETECTED,
                    stored.getUserId(),
                    Map.of("familyId", stored.getFamilyId().toString()));
            throw invalidToken();
        }

        if (!stored.isUsable()) {
            throw invalidToken();
        }

        User user =
                users.findById(stored.getUserId()).orElseThrow(AuthenticationService::invalidToken);
        if (!user.canAuthenticate()) {
            // Suspended or locked since the token was issued.
            sessionRevocation.revokeFamily(stored.getFamilyId(), "account_not_active");
            throw invalidToken();
        }

        TokenPair pair = issuePair(user, stored.getFamilyId(), ip, userAgent);

        stored.setUsedAt(Instant.now());
        refreshTokens.save(stored);

        return pair;
    }

    /** Revokes the whole family, so every device sharing that login is signed out. */
    @Transactional
    public void logout(String presentedToken) {
        refreshTokens
                .findByTokenHash(SecureTokens.hash(presentedToken))
                .ifPresent(
                        token -> {
                            sessionRevocation.revokeFamily(token.getFamilyId(), "logout");
                            audit.recordFor(
                                    token.getUserId(),
                                    null,
                                    AuditService.USER_LOGOUT,
                                    token.getUserId(),
                                    null);
                        });
        // Absent token: nothing to do. Logout is idempotent and must not report whether the token
        // was real.
    }

    /** Signs a user out everywhere. Used when a password or role changes. */
    public void revokeAllSessions(UUID userId, String reason) {
        sessionRevocation.revokeAllForUser(userId, reason);
    }

    private TokenPair issuePair(User user, UUID familyId, String ip, String userAgent) {
        AccessTokenIssuer.IssuedToken access = accessTokenIssuer.issue(user);

        String refreshValue = SecureTokens.generate();
        Instant refreshExpiry = Instant.now().plus(jwtProperties.getRefreshTokenTtl());

        RefreshToken token = new RefreshToken();
        token.setUserId(user.getId());
        token.setFamilyId(familyId);
        token.setTokenHash(SecureTokens.hash(refreshValue));
        token.setExpiresAt(refreshExpiry);
        token.setIpAddress(ip);
        token.setUserAgent(
                userAgent != null && userAgent.length() > 255
                        ? userAgent.substring(0, 255)
                        : userAgent);
        refreshTokens.save(token);

        return new TokenPair(
                access.value(),
                access.expiresAt(),
                refreshValue,
                refreshExpiry,
                user.isMustChangePassword());
    }

    private static Errors.UnauthorizedException invalidCredentials() {
        // One message for unknown address and wrong password alike.
        return new Errors.UnauthorizedException(
                "auth.invalid_credentials", "Email or password is incorrect.");
    }

    private static Errors.UnauthorizedException invalidToken() {
        // Expired, revoked, replayed and never-existed all look the same from outside.
        return new Errors.UnauthorizedException(
                "auth.invalid_refresh_token", "Your session has ended. Please sign in again.");
    }
}
