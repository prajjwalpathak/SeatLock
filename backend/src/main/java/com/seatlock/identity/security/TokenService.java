package com.seatlock.identity.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues the app's own RS256 access tokens (for password login now, Google login on W2·D3). */
@Service
public class TokenService {

    /** Custom claims. "sub" carries the user id. */
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_ROLES = "roles";

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public AccessToken issueAccessToken(AuthenticatedUser user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(Long.toString(user.id()))       // stable id, not the e-mail (which can change)
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())        // "jti": unique per token (useful for audit/denylist)
                .claim(CLAIM_EMAIL, user.email())
                .claim(CLAIM_ROLES, List.of(user.role().name()))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build(); // encoder adds "kid"
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, expiresAt, properties.accessTokenTtl());
    }
}
