package com.aiinvestor.security;

import com.aiinvestor.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;

/**
 * Dashboard security: session login (HttpOnly cookie), CSRF double-submit for the SPA, two roles
 * (ADMIN may change state; VIEWER is read-only), nothing under /api/** or /ws/** without authentication.
 * Credentials come from the environment only. No broker or AI credentials are ever exposed through any endpoint.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean UserDetailsService users(AppProperties props, PasswordEncoder enc) {
        AppProperties.Security s = props.security();
        String adminHash;
        if (StringUtils.hasText(s.adminPasswordBcrypt())) adminHash = s.adminPasswordBcrypt();
        else if (StringUtils.hasText(s.adminPassword())) adminHash = enc.encode(s.adminPassword());
        else {
            byte[] b = new byte[18];
            new SecureRandom().nextBytes(b);
            String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            adminHash = enc.encode(generated);
            Path file = Path.of(System.getProperty("java.io.tmpdir"), "ai-investor-admin-credential.txt");
            try {
                Files.writeString(file, generated + System.lineSeparator());
                try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
                log.warn("No admin credential configured (APP_ADMIN_PASSWORD / APP_ADMIN_PASSWORD_BCRYPT). A random development credential was written to {} (owner-readable). Configure a real one for any shared deployment.", file);
            } catch (IOException e) {
                log.warn("No admin credential configured and the generated one could not be saved; admin login is impossible until APP_ADMIN_PASSWORD is set");
            }
        }
        List<UserDetails> list = new java.util.ArrayList<>();
        list.add(User.withUsername(s.adminUser()).password(adminHash).roles("ADMIN", "VIEWER").build());
        if (StringUtils.hasText(s.viewerUser()) && StringUtils.hasText(s.viewerPasswordBcrypt())) {
            list.add(User.withUsername(s.viewerUser()).password(s.viewerPasswordBcrypt()).roles("VIEWER").build());
        }
        return new InMemoryUserDetailsManager(list);
    }

    @Bean AuthenticationManager authenticationManager(UserDetailsService uds, PasswordEncoder enc) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(uds);
        p.setPasswordEncoder(enc);
        return new ProviderManager(p);
    }

    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean SecurityFilterChain filterChain(HttpSecurity http, SecurityContextRepository repo) throws Exception {
        http
            .cors(c -> c.configurationSource(corsSource(null)))
            .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
            .securityContext(sc -> sc.securityContextRepository(repo))
            .sessionManagement(sm -> sm.sessionFixation(f -> f.migrateSession()))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/csrf", "/api/auth/login").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/**", "/ws/**").authenticated()
                .anyRequest().denyAll());
        return http.build();
    }

    @Bean CorsConfigurationSource corsSource(AppProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(props == null ? List.of("http://localhost:5173") : props.security().allowedOrigins());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }

    /** SPA-friendly CSRF: the cookie value (not the XOR-masked one) is accepted from the X-XSRF-TOKEN header. */
    static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override public void handle(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            this.xor.handle(request, response, csrfToken);
            csrfToken.get();       // force cookie to be written on first request
        }

        @Override public String resolveCsrfTokenValue(jakarta.servlet.http.HttpServletRequest request, CsrfToken csrfToken) {
            String header = request.getHeader(csrfToken.getHeaderName());
            return StringUtils.hasText(header) ? this.plain.resolveCsrfTokenValue(request, csrfToken) : this.xor.resolveCsrfTokenValue(request, csrfToken);
        }
    }
}
