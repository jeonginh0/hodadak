package com.hodadak.backend.auth.application;

import com.hodadak.backend.auth.infra.*;
import com.hodadak.backend.user.application.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("api")
public class AuthService {
    private final KakaoClient kakao;
    private final UserService users;
    private final JwtTokens jwt;
    private final RefreshTokens refresh;

    public AuthService(KakaoClient kakao, UserService users, JwtTokens jwt, RefreshTokens refresh) {
        this.kakao = kakao;
        this.users = users;
        this.jwt = jwt;
        this.refresh = refresh;
    }

    public record LoginResult(String accessToken, UserView user, String refreshToken) {}
    public record RefreshResult(String accessToken, String refreshToken) {}

    public LoginResult login(String code, String redirectUri) {
        var identity = kakao.authenticate(code, redirectUri);
        var user = users.login(identity.kakaoId(), identity.nickname());
        String access = jwt.issue(user.id());
        return new LoginResult(access, user, refresh.issue(user.id()));
    }

    public RefreshResult refresh(String token) {
        var rotation = refresh.rotate(token);
        return new RefreshResult(jwt.issue(rotation.userId()), rotation.token());
    }

    public void logout(String token, long userId) { refresh.revoke(token, userId); }
}
