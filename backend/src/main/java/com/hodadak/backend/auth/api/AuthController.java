package com.hodadak.backend.auth.api;

import com.hodadak.backend.auth.application.AuthService;
import com.hodadak.backend.user.application.UserView;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("api")
@RequestMapping("/api/auth")
public class AuthController {
    public static final String REFRESH_COOKIE = "refreshToken";
    private final AuthService auth;
    private final Duration refreshTtl;

    public AuthController(AuthService auth, @Value("${hodadak.jwt.refresh-ttl-days}") long days) {
        this.auth = auth;
        this.refreshTtl = Duration.ofDays(days);
    }

    public record LoginRequest(@NotBlank @Size(max = 2048) String code,
                               @NotBlank @Size(max = 2048) String redirectUri) {}
    public record LoginResponse(String accessToken, UserView user) {}
    public record RefreshResponse(String accessToken) {}

    @PostMapping(value = "/kakao", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        var result = auth.login(request.code(), request.redirectUri());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie(result.refreshToken(), refreshTtl))
                .body(new LoginResponse(result.accessToken(), result.user()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        var result = auth.refresh(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie(result.refreshToken(), refreshTtl))
                .body(new RefreshResponse(result.accessToken()));
    }

    @PostMapping("/logout")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt,
            @CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        auth.logout(token, Long.parseLong(jwt.getSubject()));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).build();
    }

    private static String cookie(String token, Duration ttl) {
        return ResponseCookie.from(REFRESH_COOKIE, token).httpOnly(true).secure(true)
                .sameSite("Lax").path("/api/auth").maxAge(ttl).build().toString();
    }
}
