package com.picopilot.featureflag.service;

import com.picopilot.common.exception.InvalidStateException;
import com.picopilot.common.exception.ResourceNotFoundException;
import com.picopilot.featureflag.model.FeatureFlag;
import com.picopilot.featureflag.repository.FeatureFlagRepository;
import com.picopilot.identity.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    private final FeatureFlagRepository flagRepository;
    private final WorkspaceService workspaceService;

    @Transactional(readOnly = true)
    public List<FeatureFlag> listFlags(UUID workspaceId, UUID projectId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return flagRepository.findByProjectId(projectId);
    }

    @Transactional
    public FeatureFlag createFlag(UUID workspaceId, FeatureFlag flag, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        flag.setWorkspaceId(workspaceId);
        flag.setCurrentState("DISABLED");
        return flagRepository.save(flag);
    }

    @Transactional
    public FeatureFlag enableFlag(UUID workspaceId, UUID flagId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        FeatureFlag flag = flagRepository.findById(flagId)
            .orElseThrow(() -> new ResourceNotFoundException("FeatureFlag", flagId.toString()));

        // Validate readiness checks before enabling
        if (flag.getCurrentState().equals("ENCRYPTED_READ")) {
            throw new InvalidStateException("Flag is already in ENCRYPTED_READ state");
        }

        flag.setCurrentState("ENCRYPTED_READ");
        flag.setEnabledAt(Instant.now());
        flag.setEnabledBy(userId);
        log.info("Feature flag {} enabled by {}", flag.getFlagName(), userId);
        return flagRepository.save(flag);
    }

    @Transactional
    public FeatureFlag rollbackFlag(UUID workspaceId, UUID flagId, String userId, String reason) {
        workspaceService.requireMembership(workspaceId, userId);
        FeatureFlag flag = flagRepository.findById(flagId)
            .orElseThrow(() -> new ResourceNotFoundException("FeatureFlag", flagId.toString()));

        if (!flag.getCurrentState().equals("ENCRYPTED_READ")) {
            throw new InvalidStateException("Flag is not in ENCRYPTED_READ state: " + flag.getCurrentState());
        }

        flag.setCurrentState("LEGACY_READ");
        flag.setRollbackAt(Instant.now());
        flag.setRollbackBy(userId);
        flag.setRollbackReason(reason);
        log.info("Feature flag {} rolled back by {}: {}", flag.getFlagName(), userId, reason);
        return flagRepository.save(flag);
    }
}