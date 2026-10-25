package com.picopilot.identity.service;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.picopilot.common.model.UserRole;
import com.picopilot.config.LocalAuthProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Issues and verifies HS256 access tokens for local / Google logins.
 */
@Service
public class LocalTokenService {

    public static final String ISSUER = "pi-copilot-local";

    private final LocalAuthProperties properties;
    private final SecretKey key;
    private final NimbusJwtEncoder encoder;

    public LocalTokenService(LocalAuthProperties properties) {
        this.properties = properties;
        byte[] secret = properties.jwtSecret() == null ? new byte[0]
            : properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (properties.enabled() && secret.length < 32) {
            throw new IllegalStateException(
                "picopilot.security.local-auth.jwt-secret must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(secret.length == 0 ? new byte[32] : secret, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    public IssuedToken issue(String subject, String email, String name, List<UserRole> roles) {
        Instant now = Instant.now();
        long ttlSeconds = properties.tokenTtlMinutes() * 60;
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(subject)
            .issuedAt(now)
            .expiresAt(now.plus(ttlSeconds, ChronoUnit.SECONDS))
            .claim("email", email == null ? "" : email)
            .claim("name", name == null ? subject : name)
            .claim("preferred_username", email == null || email.isBlank() ? subject : email)
            .claim("roles", roles.stream().map(Enum::name).toList())
            .build();
        String token = encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new IssuedToken(token, ttlSeconds);
    }

    public JwtDecoder decoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    public record IssuedToken(String accessToken, long expiresIn) {}
}
