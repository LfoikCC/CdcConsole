package com.cdcplatform.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@Configuration
public class BackendSecurityConfiguration {
    @Bean
    SecurityFilterChain backendSecurity(HttpSecurity http, BackendProperties properties) throws Exception {
        boolean bearer = "BEARER".equalsIgnoreCase(properties.getAuth().getMode());
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfiguration(properties)))
                .authorizeHttpRequests(auth -> {
                    if (bearer) auth.requestMatchers("/api/**").authenticated();
                    auth.requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll();
                    auth.anyRequest().permitAll();
                });
        if (bearer) http.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "backend.auth", name = "mode", havingValue = "BEARER")
    JwtDecoder jwtDecoder(BackendProperties properties) {
        if (properties.getAuth().getIssuer().isBlank()) throw new IllegalStateException("backend.auth.issuer is required when backend.auth.mode=BEARER");
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(properties.getAuth().getIssuer());
        OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(properties.getAuth().getIssuer());
        OAuth2TokenValidator<Jwt> validator = issuer;
        if (!properties.getAuth().getAudience().isBlank()) {
            var expected = properties.getAuth().getAudience();
            validator = new DelegatingOAuth2TokenValidator<>(issuer,
                    new JwtClaimValidator<Collection<String>>("aud", aud -> aud != null && aud.contains(expected)));
        }
        decoder.setJwtValidator(validator);
        return decoder;
    }

    private CorsConfigurationSource corsConfiguration(BackendProperties properties) {
        var cors = new CorsConfiguration();
        List<String> origins = Arrays.stream(properties.getCorsAllowedOrigins().split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Last-Event-ID"));
        cors.setExposedHeaders(List.of("Last-Event-ID"));
        cors.setAllowCredentials(!origins.contains("*"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
