package com.hodadak.backend.auth.application;

import com.hodadak.backend.auth.infra.KakaoClient;
import com.hodadak.backend.auth.infra.JwtTokens;
import com.hodadak.backend.auth.infra.RefreshTokens;
import com.hodadak.backend.common.domain.BusinessException;
import com.hodadak.backend.user.application.UserService;
import com.hodadak.backend.user.application.UserView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private final KakaoClient kakao = mock(KakaoClient.class);
    private final UserService users = mock(UserService.class);
    private final JwtTokens jwt = mock(JwtTokens.class);
    private final RefreshTokens refresh = mock(RefreshTokens.class);
    private final AuthService service = new AuthService(kakao, users, jwt, refresh);

    @Test
    void loginExchangesCodeAndIssuesOurTokens() {
        var user = new UserView(1L, "닉네임", null, 0);
        when(kakao.authenticate("code", "https://example.com/auth/kakao/callback"))
                .thenReturn(new KakaoClient.Identity("123", "닉네임"));
        when(users.login("123", "닉네임")).thenReturn(user);
        when(jwt.issue(1L)).thenReturn("access");
        when(refresh.issue(1L)).thenReturn("refresh");

        var result = service.login("code", "https://example.com/auth/kakao/callback");

        assertEquals(user, result.user());
        assertEquals("access", result.accessToken());
        assertEquals("refresh", result.refreshToken());
    }

    @Test
    void rejectedKakaoCodeDoesNotCreateUserOrTokens() {
        when(kakao.authenticate("bad", "callback")).thenThrow(BusinessException.unauthorized());
        assertThrows(BusinessException.class, () -> service.login("bad", "callback"));
        verifyNoInteractions(users, jwt, refresh);
    }

    @Test
    void refreshUsesOwnerReturnedByAtomicRotation() {
        when(refresh.rotate("old")).thenReturn(new RefreshTokens.Rotation(2L, "new"));
        when(jwt.issue(2L)).thenReturn("access");
        var result = service.refresh("old");
        assertEquals("new", result.refreshToken());
        assertEquals("access", result.accessToken());
    }

    @Test
    void invalidRefreshCannotIssueAccessToken() {
        when(refresh.rotate("used")).thenThrow(BusinessException.unauthorized());
        assertThrows(BusinessException.class, () -> service.refresh("used"));
        verifyNoInteractions(jwt);
    }
}
