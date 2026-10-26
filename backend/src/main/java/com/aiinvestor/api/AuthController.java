package com.aiinvestor.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 200) String password) {}

    private final AuthenticationManager manager;
    private final SecurityContextRepository repo;
    private final com.aiinvestor.persistence.AuditRepository audit;

    public AuthController(AuthenticationManager manager, SecurityContextRepository repo, com.aiinvestor.persistence.AuditRepository audit) {
        this.manager = manager;
        this.repo = repo;
        this.audit = audit;
    }

    /** Touching this endpoint makes the server set the XSRF-TOKEN cookie the SPA echoes back in X-XSRF-TOKEN. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) { return Map.of("header", token.getHeaderName()); }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        try {
            Authentication auth = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);
            req.getSession(true);
            req.changeSessionId();
            repo.saveContext(ctx, req, res);
            audit.log(body.username(), "LOGIN_OK", "session", null, null);
            return ResponseEntity.ok(me(auth));
        } catch (AuthenticationException e) {
            audit.log(body.username().length() > 64 ? "?" : body.username(), "LOGIN_FAILED", "session", null, null);
            return ResponseEntity.status(401).body(Map.of("error", "invalid_credentials"));
        }
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest req) {
        if (req.getSession(false) != null) req.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
        return Map.of("status", "logged_out");
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        List<String> roles = auth.getAuthorities().stream().map(a -> a.getAuthority().replace("ROLE_", "")).sorted().toList();
        return Map.of("username", auth.getName(), "roles", roles, "admin", roles.contains("ADMIN"));
    }
}
