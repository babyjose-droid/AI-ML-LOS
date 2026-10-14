package com.rhythm.los.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final SecretKey key;
    private final com.rhythm.los.tenant.TenantRegistry registry;

    public SecurityConfig(@Value("${rhythm.jwt.secret}") String secret, com.rhythm.los.tenant.TenantRegistry registry) {
        this.registry = registry;
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("rhythm.jwt.secret must be at least 32 bytes");
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                    .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                    .requestMatchers("/actuator/health/**", "/actuator/info", "/api/docs/**", "/api/openapi/**",
                            "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    // role rules at the edge, so a forbidden caller is refused before the body is even read;
                    // the same rules are repeated with @PreAuthorize on each controller method
                    // video KYC customer pages: the one-time link token is the credential
                    .requestMatchers("/api/vkyc/c/**").permitAll()
                    .requestMatchers("/api/party/c/**").permitAll()
                    // borrower portal: OTP sign-in is open; everything else needs a borrower token, and borrowers reach nothing else
                    .requestMatchers(HttpMethod.POST, "/api/borrower/otp", "/api/borrower/otp/verify").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/borrower/products").permitAll()
                    .requestMatchers("/api/borrower/**").hasRole("BORROWER")
                    .requestMatchers(HttpMethod.POST, "/api/vkyc/**", "/api/applications/*/vkyc").hasAnyRole("OPERATIONS", "ADMIN", "CREDIT_OFFICER", "CREDIT_MANAGER")
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/api/audit/**", "/api/audit").hasAnyRole("ADMIN", "COMPLIANCE", "CRO", "CREDIT_MANAGER")
                    .requestMatchers(HttpMethod.GET, "/api/integrations/**").hasAnyRole("ADMIN", "OPERATIONS", "COMPLIANCE")
                    .requestMatchers(HttpMethod.POST, "/api/integrations/**").hasAnyRole("ADMIN", "OPERATIONS")
                    .requestMatchers(HttpMethod.POST, "/api/applications/*/fraud/**").hasRole("FRAUD_ANALYST")
                    .requestMatchers(HttpMethod.POST, "/api/applications/*/sanction", "/api/applications/*/sanction/**")
                        .hasAnyRole("CREDIT_OFFICER", "CREDIT_MANAGER", "CRO")
                    // processing steps (KYC, documents, data, decision, field visit, disbursement) are open to staff here;
                    // the controller checks the role the product's workflow assigns to the stage (StageAccess)
                    .requestMatchers(HttpMethod.POST, "/api/applications", "/api/applications/*/submit", "/api/applications/*/withdraw",
                            "/api/applications/*/documents", "/api/applications/*/kfs/**").hasAnyRole("SALES", "OPERATIONS", "ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/applications/*").hasAnyRole("SALES", "OPERATIONS", "ADMIN")
                    .requestMatchers("/api/**").hasAnyRole("ADMIN", "SALES", "OPERATIONS", "CREDIT_OFFICER", "CREDIT_MANAGER", "CRO", "FRAUD_ANALYST", "COMPLIANCE", "FIELD_OFFICER", "LEGAL_REVIEWER", "VALUER")
                    .anyRequest().permitAll())
            .exceptionHandling(e -> e.accessDeniedHandler((req, res, ex) ->
                    json(res, 403, "FORBIDDEN", "Your role is not allowed to do this")))
            .addFilterAfter(new com.rhythm.los.tenant.TenantFilter(registry), org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
            .oauth2ResourceServer(o -> o
                    .jwt(j -> j.jwtAuthenticationConverter(authConverter()))
                    .authenticationEntryPoint((req, res, ex) -> json(res, 401, "UNAUTHENTICATED", "Sign in again: the session is missing or has expired"))
                    .accessDeniedHandler((req, res, ex) -> json(res, 403, "FORBIDDEN", "Your role is not allowed to do this")));
        return http.build();
    }

    private static void json(jakarta.servlet.http.HttpServletResponse res, int status, String code, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private JwtAuthenticationConverter authConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(roles);
        return c;
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
