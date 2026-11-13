package com.hodadak.backend.auth;

import com.hodadak.backend.auth.infra.*;
import com.hodadak.backend.common.domain.BusinessException;
import com.hodadak.backend.user.application.UserService;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = {
        "spring.profiles.active=api", "hodadak.jwt.access-secret=test-access-secret-0123456789abcdef0123456789",
        "hodadak.public-base-url=https://example.com",
        // Docker Desktop's first Redis handshake may exceed the production command timeout.
        "spring.data.redis.timeout=10s"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired UserService users;
    @Autowired RefreshTokens refresh;
    @Autowired JwtTokens jwt;
    @Autowired StringRedisTemplate redis;
    @MockitoBean KakaoClient kakao;

    @Test
    void loginMeRefreshAndLogoutLifecycle() throws Exception {
        when(kakao.authenticate("code", "https://example.com/auth/kakao/callback"))
                .thenReturn(new KakaoClient.Identity("100", "테스트"));
        var response = mvc.perform(post("/api/auth/kakao").header("Origin", "https://example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"code":"code","redirectUri":"https://example.com/auth/kakao/callback"}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.nickname").value("테스트"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        Cookie cookie = response.getCookie("refreshToken");
        assertNotNull(cookie);
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertEquals("/api/auth", cookie.getPath());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
        assertEquals(14 * 86400, cookie.getMaxAge());
        String access = json.readTree(response.getContentAsString()).get("accessToken").asText();
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("테스트"));
        mvc.perform(patch("/api/me").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"01012345678\",\"nickname\":\"변경\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phone").value("01012345678"));
        assertEquals("변경", users.login("100", "카카오닉네임").nickname());

        var rotated = mvc.perform(post("/api/auth/refresh").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("refreshToken");
        assertNotNull(rotated);
        assertNotEquals(cookie.getValue(), rotated.getValue());
        mvc.perform(post("/api/auth/refresh").cookie(cookie)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").cookie(rotated).header("Authorization", "Bearer " + access))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("refreshToken", 0));
        mvc.perform(post("/api/auth/refresh").cookie(rotated)).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticationValidationAndOriginErrorsFollowContract() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED")).andExpect(jsonPath("$.traceId").isNotEmpty());
        mvc.perform(get("/api/me").header("Authorization", "Bearer broken"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(post("/api/auth/kakao").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields").isArray());
        mvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/auth/refresh").header("Sec-Fetch-Site", "same-site"))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshRaceHasExactlyOneWinnerAndExpiredTokensFail() throws Exception {
        String token = refresh.issue(77L);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(10)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 10; i++) futures.add(pool.submit(() -> {
                start.await();
                try { refresh.rotate(token); return true; }
                catch (BusinessException exception) { return false; }
            }));
            start.countDown();
            int winners = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
        }
        String expired = refresh.issue(77L);
        redis.expire("auth:refresh:" + expired, Duration.ZERO);
        assertThrows(BusinessException.class, () -> refresh.rotate(expired));
    }

    @Test
    void logoutCannotRevokeAnotherUsersToken() {
        String token = refresh.issue(77L);
        refresh.revoke(token, 88L);
        assertEquals(77L, refresh.rotate(token).userId());
    }

    @Test
    void invalidProfileDoesNotChangeStoredUser() throws Exception {
        var user = users.login("profile-validation", "원래이름");
        String access = jwt.issue(user.id());
        for (String body : List.of("{\"phone\":\"010-1234-5678\"}", "{\"nickname\":\"   \"}")) {
            mvc.perform(patch("/api/me").header("Authorization", "Bearer " + access)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fields").isNotEmpty());
        }
        assertEquals("원래이름", users.get(user.id()).nickname());
        assertNull(users.get(user.id()).phone());
    }

    @Test
    void simultaneousFirstLoginsProduceOneUser() throws Exception {
        String kakaoId = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(10)) {
            var futures = new ArrayList<Future<Long>>();
            for (int i = 0; i < 10; i++) futures.add(pool.submit(() -> {
                start.await();
                return users.login(kakaoId, "동시로그인").id();
            }));
            start.countDown();
            var ids = new HashSet<Long>();
            for (var future : futures) ids.add(future.get(20, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
        }
    }
}
