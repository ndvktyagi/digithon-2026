package com.picopilot.identity.service;

import com.picopilot.common.model.UserRole;
import com.picopilot.config.LocalAuthProperties;
import com.picopilot.identity.model.Workspace;
import com.picopilot.identity.model.WorkspaceMember;
import com.picopilot.identity.repository.WorkspaceMemberRepository;
import com.picopilot.identity.repository.WorkspaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class LocalAuthService {

    private static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> GOOGLE_ISSUERS =
        Set.of("accounts.google.com", "https://accounts.google.com");
    private static final String DEFAULT_WORKSPACE_SLUG = "default";

    private final LocalAuthProperties properties;
    private final LocalTokenService tokenService;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final NimbusJwtDecoder googleDecoder;

    public LocalAuthService(LocalAuthProperties properties, LocalTokenService tokenService,
                            WorkspaceRepository workspaceRepository,
                            WorkspaceMemberRepository memberRepository) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.googleDecoder = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS).build();
        this.googleDecoder.setJwtValidator(googleValidator());
    }

    @Transactional
    public LocalTokenService.IssuedToken loginWithPassword(String username, String password) {
        requireEnabled();
        if (!constantTimeEquals(username, properties.adminUsername())
                || !constantTimeEquals(password, properties.adminPassword())) {
            throw new BadCredentialsException("Invalid username or password");
        }
        String subject = "local:" + username;
        ensureDefaultWorkspace(subject, username, null);
        log.info("Local admin {} signed in", username);
        return tokenService.issue(subject, null, username, List.of(UserRole.PLATFORM_ADMIN));
    }

    @Transactional
    public LocalTokenService.IssuedToken loginWithGoogle(String credential) {
        if (!properties.googleEnabled()) {
            throw new BadCredentialsException("Google sign-in is not configured");
        }
        Jwt google;
        try {
            google = googleDecoder.decode(credential);
        } catch (JwtException e) {
            throw new BadCredentialsException("Invalid Google credential", e);
        }
        if (!Boolean.TRUE.equals(google.getClaimAsBoolean("email_verified"))) {
            throw new BadCredentialsException("Google email is not verified");
        }
        String email = google.getClaimAsString("email");
        String name = google.getClaimAsString("name");
        String subject = "google:" + google.getSubject();

        boolean admin = properties.adminEmails().stream().anyMatch(e -> e.equalsIgnoreCase(email));
        List<UserRole> roles = admin ? List.of(UserRole.PLATFORM_ADMIN) : List.of(UserRole.APP_DEVELOPER);
        if (admin) {
            ensureDefaultWorkspace(subject, name, email);
        }
        log.info("Google user {} signed in (admin={})", email, admin);
        return tokenService.issue(subject, email, name, roles);
    }

    /** Makes sure an admin always has at least one workspace to land in. */
    private void ensureDefaultWorkspace(String userId, String userName, String email) {
        if (!memberRepository.findByUserIdAndActiveTrue(userId).isEmpty()) {
            return;
        }
        Workspace workspace = workspaceRepository.findBySlug(DEFAULT_WORKSPACE_SLUG).orElseGet(() -> {
            Workspace ws = new Workspace();
            ws.setName("Default Workspace");
            ws.setSlug(DEFAULT_WORKSPACE_SLUG);
            ws.setDescription("Created automatically on first admin sign-in");
            return workspaceRepository.save(ws);
        });
        WorkspaceMember member = memberRepository
            .findByWorkspaceIdAndUserId(workspace.getId(), userId)
            .orElseGet(WorkspaceMember::new);
        member.setWorkspace(workspace);
        member.setUserId(userId);
        member.setUserName(userName);
        member.setUserEmail(email);
        member.setRole(UserRole.PLATFORM_ADMIN);
        member.setActive(true);
        memberRepository.save(member);
    }

    private OAuth2TokenValidator<Jwt> googleValidator() {
        OAuth2TokenValidator<Jwt> issuerAndAudience = jwt -> {
            String iss = jwt.getClaimAsString("iss");
            boolean ok = iss != null && GOOGLE_ISSUERS.contains(iss)
                && jwt.getAudience() != null
                && jwt.getAudience().contains(properties.googleClientId());
            return ok ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Wrong issuer or audience", null));
        };
        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuerAndAudience);
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new BadCredentialsException("Local login is disabled");
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
