package com.hodadak.backend.auth.infra;

import com.hodadak.backend.common.api.ApiErrors;
import com.hodadak.backend.common.domain.ErrorCode;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@Profile("api")
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class SecurityConfiguration {
    @Bean
    JwtDecoder jwtDecoder(JwtTokens tokens) { return tokens.decoder(); }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder decoder, JsonMapper mapper,
            @Value("${hodadak.public-base-url}") String publicBaseUrl,
            @Value("${server.port:8080}") int serverPort, Environment environment) throws Exception {
        boolean localSwagger = environment.acceptsProfiles(Profiles.of("local & !prod"));
        var unauthorized = (org.springframework.security.web.AuthenticationEntryPoint)
                (request, response, exception) -> ApiErrors.write(response, ErrorCode.AUTH_REQUIRED, mapper);
        var forbidden = (org.springframework.security.web.access.AccessDeniedHandler)
                (request, response, exception) -> ApiErrors.write(response, ErrorCode.FORBIDDEN, mapper);
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
                .csrf(csrf -> csrf.disable())
                .addFilterBefore(new OriginFilter(publicBaseUrl, mapper, localSwagger, serverPort),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/api/auth/kakao", "/api/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/**", "/api/docs", "/api/docs/**",
                                "/swagger-ui/**", "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.decoder(decoder))
                        .authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden));
        return http.build();
    }

    static final class OriginFilter extends OncePerRequestFilter {
        private final URI trusted;
        private final URI localSwagger;
        private final JsonMapper mapper;

        OriginFilter(String publicBaseUrl, JsonMapper mapper, boolean allowLocalSwagger, int serverPort) {
            this.trusted = URI.create(publicBaseUrl);
            this.localSwagger = allowLocalSwagger ? URI.create("http://localhost:" + serverPort) : null;
            this.mapper = mapper;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            if ("POST".equals(request.getMethod()) && request.getRequestURI().startsWith("/api/auth/")) {
                String origin = request.getHeader("Origin");
                String site = request.getHeader("Sec-Fetch-Site");
                if ((origin != null && !sameOrigin(origin, trusted)
                        && !(localSwagger != null && sameOrigin(origin, localSwagger))) || "cross-site".equals(site)
                        || (origin == null && "same-site".equals(site))) {
                    ApiErrors.write(response, ErrorCode.FORBIDDEN, mapper);
                    return;
                }
            }
            chain.doFilter(request, response);
        }

        private boolean sameOrigin(String origin, URI expected) {
            try {
                URI uri = URI.create(origin);
                return uri.getHost() != null && uri.getHost().equalsIgnoreCase(expected.getHost())
                        && expected.getScheme().equalsIgnoreCase(uri.getScheme())
                        && port(uri) == port(expected) && uri.getUserInfo() == null
                        && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                        && uri.getQuery() == null && uri.getFragment() == null;
            } catch (IllegalArgumentException exception) { return false; }
        }

        private int port(URI uri) {
            return uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
        }
    }
}
