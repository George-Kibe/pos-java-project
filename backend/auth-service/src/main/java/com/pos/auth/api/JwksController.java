package com.pos.auth.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.security.JwtKeyProvider;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Publishes the public keys other services use to verify access tokens.
 *
 * <p>Public by necessity - a verifier has to be able to fetch it before it can authenticate
 * anything - and safe to be public, because it contains only public key material.
 *
 * <p>Every key is published, not just the active one, which is what allows rotation without
 * downtime: the new key is published and picked up by verifiers before anything is signed with it.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Keys")
public class JwksController {

    private final JwtKeyProvider keyProvider;

    @GetMapping("/.well-known/jwks.json")
    @Operation(summary = "JSON Web Key Set for verifying access tokens")
    public Map<String, Object> jwks() {
        return keyProvider.publicJwkSet().toJSONObject();
    }
}
