package com.hodadak.backend.auth.infra;

import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.junit.jupiter.api.Assertions.*;

class JwtTokensTest {
    private static final String SECRET = "test-only-secret-0123456789abcdef0123456789";
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");

    @Test
    void tokenHasUserSubjectAndThirtyMinuteExpiry() {
        var tokens = new JwtTokens(SECRET, 30, Clock.fixed(NOW, ZoneOffset.UTC));
        var jwt = tokens.decoder().decode(tokens.issue(42L));
        assertEquals("42", jwt.getSubject());
        assertEquals(NOW.plusSeconds(1800), jwt.getExpiresAt());
    }

    @Test
    void expiredAndWrongSignatureTokensAreRejected() {
        var tokens = new JwtTokens(SECRET, 30, Clock.fixed(NOW, ZoneOffset.UTC));
        String token = tokens.issue(42L);
        var later = new JwtTokens(SECRET, 30, Clock.fixed(NOW.plusSeconds(1800), ZoneOffset.UTC));
        assertThrows(JwtException.class, () -> later.decoder().decode(token));
        var other = new JwtTokens(SECRET + "different", 30, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(JwtException.class, () -> other.decoder().decode(token));
    }
}
