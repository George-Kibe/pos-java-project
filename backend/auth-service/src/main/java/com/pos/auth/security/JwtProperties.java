package com.pos.auth.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Token issuance settings. */
@ConfigurationProperties(prefix = "pos.jwt")
public class JwtProperties {

    /** The {@code iss} claim, and the value every other service validates against. */
    private String issuer = "http://localhost:8081";

    /**
     * Access token lifetime. Short on purpose: an access token cannot be revoked once issued, so
     * its lifetime is the window in which a stolen one remains useful.
     */
    private Duration accessTokenTtl = Duration.ofMinutes(15);

    /** Refresh token lifetime. Rotated on every use, so this is the idle timeout, not a cap. */
    private Duration refreshTokenTtl = Duration.ofDays(7);

    private final Keystore keystore = new Keystore();

    public static class Keystore {
        /** PKCS12 keystore holding the signing key. Left empty, an ephemeral key is generated. */
        private String path;

        private String password;

        /**
         * Alias of the key to sign with. Other keys in the store are published for verification.
         */
        private String activeAlias;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getActiveAlias() {
            return activeAlias;
        }

        public void setActiveAlias(String activeAlias) {
            this.activeAlias = activeAlias;
        }
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public Keystore getKeystore() {
        return keystore;
    }
}
