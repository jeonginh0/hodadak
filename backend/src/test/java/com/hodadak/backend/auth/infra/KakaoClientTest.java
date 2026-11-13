package com.hodadak.backend.auth.infra;

import com.hodadak.backend.common.domain.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoClientTest {
    private static final String CALLBACK = "https://example.com/auth/kakao/callback";
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final KakaoClient client = new KakaoClient(builder.build(), "client", "secret", CALLBACK);

    @Test
    void exchangesCodeAndUsesProviderBearerToken() {
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=authorization_code")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("code=one-use-code")))
                .andRespond(withSuccess("{\"access_token\":\"provider-token\",\"expires_in\":21600}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andExpect(header("Authorization", "Bearer provider-token"))
                .andRespond(withSuccess("""
                    {"id":12345,"connected_at":"2026-11-13T00:00:00Z","kakao_account":{"profile":{"nickname":"호다닥","is_default_nickname":false}}}
                    """, MediaType.APPLICATION_JSON));
        assertEquals(new KakaoClient.Identity("12345", "호다닥"), client.authenticate("one-use-code", CALLBACK));
        server.verify();
    }

    @Test
    void rejectsUnregisteredRedirectBeforeSendingCode() {
        assertThrows(BusinessException.class, () -> client.authenticate("code", "https://evil.example/callback"));
        server.verify();
    }

    @Test
    void rejectsInvalidGrantWithoutLeakingProviderResponse() {
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andRespond(withBadRequest().body("sensitive-code"));
        var error = assertThrows(BusinessException.class, () -> client.authenticate("code", CALLBACK));
        assertFalse(error.getMessage().contains("sensitive-code"));
        assertNull(error.getCause());
        server.verify();
    }

    @Test
    void missingKakaoIdentityCannotAuthenticate() {
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andRespond(withSuccess("{\"access_token\":\"provider-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andRespond(withSuccess("{\"kakao_account\":{\"profile\":{\"nickname\":\"이름\"}}}", MediaType.APPLICATION_JSON));
        assertThrows(BusinessException.class, () -> client.authenticate("code", CALLBACK));
        server.verify();
    }
}
