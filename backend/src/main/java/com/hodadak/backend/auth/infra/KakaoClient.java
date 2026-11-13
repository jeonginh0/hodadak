package com.hodadak.backend.auth.infra;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hodadak.backend.common.domain.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;

@Component
@Profile("api")
public class KakaoClient {
    private final RestClient client;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    @Autowired
    public KakaoClient(@Value("${hodadak.kakao.client-id}") String clientId,
                       @Value("${hodadak.kakao.client-secret}") String clientSecret,
                       @Value("${hodadak.kakao.redirect-uri}") String redirectUri) {
        this(buildClient(), clientId, clientSecret, redirectUri);
    }

    KakaoClient(RestClient client, String clientId, String clientSecret, String redirectUri) {
        this.client = client;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    private static RestClient buildClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    public record Identity(String kakaoId, String nickname) {}
    record Token(@JsonProperty("access_token") String accessToken) {}
    record Profile(String nickname) {}
    record Account(Profile profile) {}
    record KakaoUser(Long id, @JsonProperty("kakao_account") Account account) {}

    public Identity authenticate(String code, String requestedRedirect) {
        if (!redirectUri.equals(requestedRedirect)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if (clientId.isBlank()) throw BusinessException.unauthorized();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", clientId);
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        if (!clientSecret.isBlank()) form.add("client_secret", clientSecret);
        try {
            Token token = client.post().uri("https://kauth.kakao.com/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(Token.class);
            if (token == null || token.accessToken() == null || token.accessToken().isBlank())
                throw BusinessException.unauthorized();
            KakaoUser user = client.get().uri("https://kapi.kakao.com/v2/user/me")
                    .headers(headers -> headers.setBearerAuth(token.accessToken()))
                    .retrieve().body(KakaoUser.class);
            if (user == null || user.id() == null || user.id() <= 0 || user.account() == null
                    || user.account().profile() == null) throw BusinessException.unauthorized();
            String nickname = user.account().profile().nickname();
            if (nickname == null || nickname.isBlank() || nickname.length() > 50)
                throw BusinessException.unauthorized();
            return new Identity(user.id().toString(), nickname);
        } catch (RestClientException exception) {
            // Never propagate provider response bodies: they may contain codes or personal data.
            throw BusinessException.unauthorized();
        }
    }
}
