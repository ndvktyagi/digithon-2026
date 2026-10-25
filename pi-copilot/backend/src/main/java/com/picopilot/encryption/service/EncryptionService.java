package com.picopilot.encryption.service;

import com.picopilot.common.event.StageWorkCompletedEvent;
import com.picopilot.common.exception.EncryptionException;
import com.picopilot.common.exception.InvalidStateException;
import com.picopilot.common.exception.ResourceNotFoundException;
import com.picopilot.common.exception.ValidationException;
import com.picopilot.common.model.WorkspaceStage;
import com.picopilot.encryption.local.LocalFieldCipher;
import com.picopilot.encryption.model.EncryptionProviderConfig;
import com.picopilot.encryption.model.KeyMetadata;
import com.picopilot.encryption.repository.EncryptionProviderConfigRepository;
import com.picopilot.encryption.repository.KeyMetadataRepository;
import com.picopilot.identity.service.WorkspaceService;
import com.picopilot.migration.repository.MigrationPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EncryptionService {

    public static final String LOCAL_DEV = "LOCAL_DEV";

    private final EncryptionProviderConfigRepository configRepository;
    private final LocalFieldCipher localCipher;
    private final KeyMetadataRepository keyRepository;
    private final MigrationPlanRepository migrationPlanRepository;
    private final WorkspaceService workspaceService;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public List<EncryptionProviderConfig> listProviders(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return configRepository.findByWorkspaceId(workspaceId);
    }

    @Transactional
    public EncryptionProviderConfig createProvider(UUID workspaceId,
                                                     EncryptionProviderConfig config,
                                                     String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        config.setWorkspaceId(workspaceId);
        config.setVerified(false);
        applyProviderType(config);
        if (config.isDevelopment()) {
            log.warn("Creating LOCAL_DEV encryption provider in workspace {}. " +
                     "This must not be used in production.", workspaceId);
        }
        EncryptionProviderConfig saved = configRepository.save(config);
        syncLocalKey(saved);
        return saved;
    }

    @Transactional(readOnly = true)
    public EncryptionProviderConfig getProvider(UUID workspaceId, UUID providerId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return loadProvider(workspaceId, providerId);
    }

    /** Updating settings invalidates the previous health check, so the provider must be verified again. */
    @Transactional
    public EncryptionProviderConfig updateProvider(UUID workspaceId, UUID providerId,
                                                     EncryptionProviderConfig changes, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        EncryptionProviderConfig config = loadProvider(workspaceId, providerId);
        config.setProviderType(changes.getProviderType());
        config.setName(changes.getName());
        config.setEndpoint(changes.getEndpoint());
        config.setKeyName(changes.getKeyName());
        config.setAuthMethod(changes.getAuthMethod());
        config.setAuthReference(changes.getAuthReference());
        config.setRotationPolicyDays(changes.getRotationPolicyDays());
        config.setTimeoutSeconds(changes.getTimeoutSeconds());
        config.setRetryPolicy(changes.getRetryPolicy());
        config.setHealthCheckEnabled(changes.isHealthCheckEnabled());
        config.setVerified(false);
        applyProviderType(config);
        EncryptionProviderConfig saved = configRepository.save(config);
        syncLocalKey(saved);
        log.info("Encryption provider {} updated by {}", providerId, userId);
        return saved;
    }

    @Transactional
    public void deleteProvider(UUID workspaceId, UUID providerId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        EncryptionProviderConfig config = loadProvider(workspaceId, providerId);
        if (migrationPlanRepository.existsByProviderConfigId(providerId)) {
            throw new InvalidStateException(
                "Provider " + config.getName() + " is used by a migration plan and cannot be deleted");
        }
        keyRepository.deleteAll(keyRepository.findByProviderConfigId(providerId));
        configRepository.delete(config);
        log.info("Encryption provider {} deleted by {}", providerId, userId);
    }

    private EncryptionProviderConfig loadProvider(UUID workspaceId, UUID providerId) {
        return configRepository.findById(providerId)
            .filter(c -> c.getWorkspaceId().equals(workspaceId))
            .orElseThrow(() -> new ResourceNotFoundException("EncryptionProvider", providerId.toString()));
    }

    private void applyProviderType(EncryptionProviderConfig config) {
        if (!LOCAL_DEV.equals(config.getProviderType())) {
            config.setDevelopment(false);
            return;
        }
        // The local key comes from configuration, so there is no endpoint or credential to store
        if (config.getKeyName() == null || config.getKeyName().isBlank()) {
            throw new ValidationException("Key name is required");
        }
        config.setDevelopment(true);
        config.setEndpoint(null);
        config.setAuthMethod(null);
        config.setAuthReference(null);
        config.setKeyId("local:" + config.getKeyName());
        config.setKeyVersion("1");
    }

    /** Keeps one ACTIVE key record for a LOCAL_DEV provider and retires it if the provider changes type. */
    private void syncLocalKey(EncryptionProviderConfig config) {
        KeyMetadata active = keyRepository.findByProviderConfigId(config.getId()).stream()
            .filter(k -> "ACTIVE".equals(k.getKeyState()))
            .filter(k -> k.getKeyIdentifier() != null && k.getKeyIdentifier().startsWith("local:"))
            .findFirst().orElse(null);

        if (!config.isDevelopment()) {
            if (active != null) {
                active.setKeyState("RETIRED");
                active.setRetiredAt(Instant.now());
                keyRepository.save(active);
            }
            return;
        }
        if (active == null) {
            active = new KeyMetadata();
            active.setWorkspaceId(config.getWorkspaceId());
            active.setProviderConfigId(config.getId());
            active.setKeyVersion(1);
            active.setKeyType("DEK");
            active.setKeyState("ACTIVE");
            active.setActivatedAt(Instant.now());
        }
        active.setKeyAlias(config.getKeyName());
        active.setKeyIdentifier(config.getKeyId());
        keyRepository.save(active);
    }

    @Transactional
    public EncryptionProviderConfig validateProvider(UUID workspaceId, UUID providerId,
                                                       String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        EncryptionProviderConfig config = loadProvider(workspaceId, providerId);

        try {
            log.info("Health checking encryption provider {} (type: {})",
                providerId, config.getProviderType());
            if (LOCAL_DEV.equals(config.getProviderType())) {
                // Round-trip a probe value through the local key
                String probe = "pi-copilot-health-check-" + UUID.randomUUID();
                if (!probe.equals(localCipher.decrypt(localCipher.encrypt(probe)))) {
                    throw new IllegalStateException("Local key round-trip returned a different value");
                }
            }
            // Actual implementation would call KMS/Vault health endpoint for cloud providers
            config.setVerified(true);
            EncryptionProviderConfig saved = configRepository.save(config);
            events.publishEvent(new StageWorkCompletedEvent(workspaceId, WorkspaceStage.ENCRYPTION_SETUP, userId));
            return saved;
        } catch (Exception e) {
            config.setVerified(false);
            configRepository.save(config);
            throw new EncryptionException("Provider health check failed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public KeyMetadata registerKey(UUID workspaceId, UUID providerId,
                                    KeyMetadata key, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        if (!configRepository.existsById(providerId)) {
            throw new ResourceNotFoundException("EncryptionProvider", providerId.toString());
        }
        key.setWorkspaceId(workspaceId);
        key.setProviderConfigId(providerId);
        key.setActivatedAt(Instant.now());
        key.setKeyState("ACTIVE");
        return keyRepository.save(key);
    }

    @Transactional
    public KeyMetadata rotateKey(UUID workspaceId, UUID keyId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        KeyMetadata existingKey = keyRepository.findById(keyId)
            .orElseThrow(() -> new ResourceNotFoundException("KeyMetadata", keyId.toString()));

        // Mark old key as DEPRECATED
        existingKey.setKeyState("DEPRECATED");
        existingKey.setRetiredAt(Instant.now());
        keyRepository.save(existingKey);

        // Create new key version
        KeyMetadata newKey = new KeyMetadata();
        newKey.setWorkspaceId(workspaceId);
        newKey.setProviderConfigId(existingKey.getProviderConfigId());
        newKey.setKeyAlias(existingKey.getKeyAlias());
        newKey.setKeyVersion(existingKey.getKeyVersion() + 1);
        newKey.setKeyType(existingKey.getKeyType());
        newKey.setKeyState("ACTIVE");
        newKey.setActivatedAt(Instant.now());
        newKey.setRotationPolicy(existingKey.getRotationPolicy());

        log.info("Key {} rotated: old version {} deprecated, new version {} activated",
            keyId, existingKey.getKeyVersion(), newKey.getKeyVersion());
        return keyRepository.save(newKey);
    }
}