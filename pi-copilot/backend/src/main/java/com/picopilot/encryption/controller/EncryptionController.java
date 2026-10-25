package com.picopilot.encryption.controller;

import com.picopilot.encryption.model.EncryptionProviderConfig;
import com.picopilot.encryption.model.KeyMetadata;
import com.picopilot.encryption.service.EncryptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class EncryptionController {

    private final EncryptionService encryptionService;

    @GetMapping("/workspaces/{workspaceId}/encryption-providers")
    public List<EncryptionProviderConfig> listProviders(@PathVariable UUID workspaceId,
                                                          @AuthenticationPrincipal Jwt jwt) {
        return encryptionService.listProviders(workspaceId, jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/encryption-providers")
    @ResponseStatus(HttpStatus.CREATED)
    public EncryptionProviderConfig createProvider(@PathVariable UUID workspaceId,
                                                     @Valid @RequestBody EncryptionProviderConfig config,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return encryptionService.createProvider(workspaceId, config, jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/encryption-providers/{providerId}/validate")
    public EncryptionProviderConfig validateProvider(@PathVariable UUID workspaceId,
                                                       @PathVariable UUID providerId,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return encryptionService.validateProvider(workspaceId, providerId, jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/encryption-providers/{providerId}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public KeyMetadata registerKey(@PathVariable UUID workspaceId,
                                     @PathVariable UUID providerId,
                                     @Valid @RequestBody KeyMetadata key,
                                     @AuthenticationPrincipal Jwt jwt) {
        return encryptionService.registerKey(workspaceId, providerId, key, jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/keys/{keyId}/rotate")
    public KeyMetadata rotateKey(@PathVariable UUID workspaceId,
                                   @PathVariable UUID keyId,
                                   @AuthenticationPrincipal Jwt jwt) {
        return encryptionService.rotateKey(workspaceId, keyId, jwt.getSubject());
    }
}