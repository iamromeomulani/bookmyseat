package com.bookmyseat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.bookmyseat.config.SecurityProperties;
import com.bookmyseat.dto.TokenRequest;
import com.bookmyseat.dto.TokenResponse;
import com.bookmyseat.exception.ApiException;

/**
 * A tiny built-in "identity provider" so the API can be exercised end to end.
 * In production this role belongs to a real IdP (e.g. Keycloak); only the decoder
 * config would change, not the controllers.
 */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final SecurityProperties props;

    public TokenService(JwtEncoder encoder, SecurityProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public TokenResponse issue(TokenRequest req) {
        boolean admin = false;
        if (req.adminKey() != null && !req.adminKey().isEmpty()) {
            if (!constantTimeEquals(req.adminKey(), props.adminApiKey())) {
                throw ApiException.unauthorized("invalid_admin_key", "Admin key is not valid");
            }
            admin = true;
        }
        String role = admin ? "ADMIN" : "USER";

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(req.userId())            // <- this is the identity every endpoint will trust
                .issuedAt(now)
                .expiresAt(now.plus(props.tokenTtl()))
                .claim("role", role)
                .build();
        String token = encoder.encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        return new TokenResponse(token, "Bearer", props.tokenTtl().toSeconds(), req.userId(), role);
    }

    /** Compare secrets without leaking, via timing, how many leading characters matched. */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
