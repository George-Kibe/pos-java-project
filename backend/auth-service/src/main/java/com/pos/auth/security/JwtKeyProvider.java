package com.pos.auth.security;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

/**
 * Holds the RSA keys this service signs with and publishes.
 *
 * <p>Supports rotation by design: the keystore may hold several keys, all of which are published at
 * the JWKS endpoint, while only the active alias signs. That ordering is what makes rotation safe -
 * publish the new key first so every service has fetched it, then switch signing to it, and only
 * retire the old key once the last token signed with it has expired. Retiring a key before then
 * invalidates live sessions.
 *
 * <p>With no keystore configured a key is generated in memory. That is a development convenience
 * and is logged loudly: the key changes on every restart, so every token issued before the restart
 * stops verifying, and replicas would each sign with a different key.
 */
public class JwtKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyProvider.class);

    private static final int KEY_SIZE = 2048;

    private final JWKSet jwkSet;
    private final RSAKey activeKey;

    public JwtKeyProvider(JwtProperties properties) {
        JwtProperties.Keystore config = properties.getKeystore();
        List<RSAKey> keys = new ArrayList<>();

        if (config.getPath() != null && !config.getPath().isBlank()) {
            Path path = Path.of(config.getPath());
            if (!Files.exists(path)) {
                throw new IllegalStateException(
                        "pos.jwt.keystore.path points at a file that does not exist: " + path);
            }
            keys.addAll(loadKeystore(path, config.getPassword()));
            log.info("Loaded {} signing key(s) from {}", keys.size(), path);
        } else {
            keys.add(generateKey());
            log.warn(
                    "No pos.jwt.keystore.path configured - generated an ephemeral RSA key."
                            + " Tokens will stop verifying when this service restarts, and replicas"
                            + " will each sign with a different key. Development only.");
        }

        if (keys.isEmpty()) {
            throw new IllegalStateException("No RSA signing keys available");
        }

        this.activeKey = selectActive(keys, config.getActiveAlias());
        this.jwkSet = new JWKSet(Collections.unmodifiableList(new ArrayList<>(keys)));
        log.info("Active signing key id: {}", activeKey.getKeyID());
    }

    /** The key currently used for signing. */
    public RSAKey activeKey() {
        return activeKey;
    }

    /** Every key, public parts only - this is what the JWKS endpoint serves. */
    public JWKSet publicJwkSet() {
        return jwkSet.toPublicJWKSet();
    }

    /** All keys including private parts, for the encoder's key source. */
    public JWKSet jwkSet() {
        return jwkSet;
    }

    private static RSAKey selectActive(List<RSAKey> keys, String alias) {
        if (alias == null || alias.isBlank()) {
            return keys.get(0);
        }
        return keys.stream()
                .filter(key -> alias.equals(key.getKeyID()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "pos.jwt.keystore.active-alias '%s' is not in the keystore"
                                                .formatted(alias)));
    }

    private static List<RSAKey> loadKeystore(Path path, String password) {
        char[] secret = password == null ? new char[0] : password.toCharArray();
        List<RSAKey> keys = new ArrayList<>();
        try (InputStream in = Files.newInputStream(path)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, secret);

            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, secret);
                Certificate certificate = keyStore.getCertificate(alias);
                if (privateKey instanceof RSAPrivateKey rsaPrivate
                        && certificate != null
                        && certificate.getPublicKey() instanceof RSAPublicKey rsaPublic) {
                    // The alias becomes the kid, so rotating means adding an alias.
                    keys.add(
                            new RSAKey.Builder(rsaPublic)
                                    .privateKey(rsaPrivate)
                                    .keyID(alias)
                                    .build());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not load JWT keystore from " + path, e);
        }
        return keys;
    }

    private static RSAKey generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate an RSA key", e);
        }
    }
}
