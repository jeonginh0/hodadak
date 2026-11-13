package com.hodadak.backend.auth.infra;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class OriginFilterTest {
    @Test
    void localSwaggerCanCallAuthEndpoints() throws Exception {
        for (String path : new String[]{"kakao", "refresh", "logout"}) {
            assertTrue(call(true, 8080, "http://localhost:8080", "same-origin", path));
        }
    }

    @Test
    void productionDoesNotTrustLocalSwagger() throws Exception {
        assertFalse(call(false, 8080, "http://localhost:8080", "same-origin", "kakao"));
    }

    @Test
    void frontendOriginRemainsAllowed() throws Exception {
        assertTrue(call(false, 8080, "http://localhost:3000", "same-origin", "kakao"));
        assertTrue(call(true, 8080, "http://localhost:3000", "same-site", "kakao"));
    }

    @Test
    void localExceptionDoesNotAllowOtherOriginsOrCrossSiteRequests() throws Exception {
        for (String origin : new String[]{"https://evil.example", "http://localhost:8081",
                "http://localhost.evil.example:8080", "null"}) {
            assertFalse(call(true, 8080, origin, "same-origin", "kakao"));
        }
        assertFalse(call(true, 8080, "http://localhost:8080", "cross-site", "refresh"));
    }

    @Test
    void localSwaggerUsesConfiguredServerPort() throws Exception {
        assertTrue(call(true, 9090, "http://localhost:9090", "same-origin", "kakao"));
        assertFalse(call(true, 9090, "http://localhost:8080", "same-origin", "kakao"));
    }

    private boolean call(boolean local, int port, String origin, String site, String endpoint) throws Exception {
        var filter = new SecurityConfiguration.OriginFilter("http://localhost:3000", new JsonMapper(), local, port);
        var request = new MockHttpServletRequest("POST", "/api/auth/" + endpoint);
        request.addHeader("Origin", origin);
        request.addHeader("Sec-Fetch-Site", site);
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> called.set(true));
        if (!called.get()) {
            assertEquals(403, response.getStatus());
            assertTrue(response.getContentAsString().contains("FORBIDDEN"));
        }
        return called.get();
    }
}
