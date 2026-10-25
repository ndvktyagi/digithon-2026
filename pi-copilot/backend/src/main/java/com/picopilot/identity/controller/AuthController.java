package com.picopilot.identity.controller;

import com.picopilot.config.LocalAuthProperties;
import com.picopilot.identity.service.LocalAuthService;
import com.picopilot.identity.service.LocalTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final LocalAuthProperties properties;
    private final LocalAuthService authService;

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record GoogleLoginRequest(@NotBlank String credential) {}

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
            "localLoginEnabled", properties.enabled(),
            "googleClientId", properties.googleEnabled() ? properties.googleClientId() : ""
        );
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        return toResponse(authService.loginWithPassword(request.username(), request.password()));
    }

    @PostMapping("/google")
    public Map<String, Object> google(@Valid @RequestBody GoogleLoginRequest request) {
        return toResponse(authService.loginWithGoogle(request.credential()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> badCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "invalid_grant", "message", ex.getMessage()));
    }

    private Map<String, Object> toResponse(LocalTokenService.IssuedToken token) {
        return Map.of(
            "access_token", token.accessToken(),
            "token_type", "Bearer",
            "expires_in", token.expiresIn()
        );
    }
}
