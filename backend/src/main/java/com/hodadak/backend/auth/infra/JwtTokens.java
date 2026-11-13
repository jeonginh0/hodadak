package com.hodadak.backend.auth.infra;

import java.nio.charset.StandardCharsets;
import java.time.*;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
@Profile("api")
public class JwtTokens {
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Clock clock;
    private final long ttlMinutes;

    public JwtTokens(@Value("${hodadak.jwt.access-secret}") String secret,
                     @Value("${hodadak.jwt.access-ttl-minutes}") long ttlMinutes, Clock clock) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32 || ttlMinutes <= 0) throw new IllegalArgumentException("Invalid JWT configuration");
        var key = new SecretKeySpec(bytes, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.clock = clock;
        this.ttlMinutes = ttlMinutes;
        this.decoder.setJwtValidator(jwt -> {
            Instant now = clock.instant();
            boolean validSubject;
            try { validSubject = Long.parseLong(jwt.getSubject()) > 0; }
            catch (RuntimeException exception) { validSubject = false; }
            if (!validSubject || !"hodadak".equals(jwt.getClaimAsString("iss"))
                    || jwt.getExpiresAt() == null || !now.isBefore(jwt.getExpiresAt())
                    || jwt.getIssuedAt() == null || jwt.getIssuedAt().isAfter(now)
                    || (jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(now))) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }
            return OAuth2TokenValidatorResult.success();
        });
    }

    public String issue(long userId) {
        Instant now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer("hodadak").subject(Long.toString(userId))
                .issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(ttlMinutes))).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public JwtDecoder decoder() { return decoder; }
}
