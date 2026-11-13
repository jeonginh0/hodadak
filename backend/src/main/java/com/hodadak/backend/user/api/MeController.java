package com.hodadak.backend.user.api;

import com.hodadak.backend.user.application.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("api")
@RequestMapping("/api/me")
@SecurityRequirement(name = "bearerAuth")
public class MeController {
    private final UserService users;
    public MeController(UserService users) { this.users = users; }

    public record UpdateRequest(
            @Size(min = 1, max = 50) @Pattern(regexp = ".*\\S.*", flags = Pattern.Flag.DOTALL) String nickname,
            @Pattern(regexp = "[0-9]{10,11}") String phone) {}

    @GetMapping
    public UserView get(@AuthenticationPrincipal Jwt jwt) {
        return users.get(Long.parseLong(jwt.getSubject()));
    }

    @PatchMapping
    public UserView update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateRequest request) {
        return users.update(Long.parseLong(jwt.getSubject()), request.nickname(), request.phone());
    }
}
