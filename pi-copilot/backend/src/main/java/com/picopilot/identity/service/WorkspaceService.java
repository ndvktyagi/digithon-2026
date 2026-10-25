package com.picopilot.identity.service;

import com.picopilot.common.exception.AccessDeniedException;
import com.picopilot.common.exception.ResourceNotFoundException;
import com.picopilot.common.exception.ValidationException;
import com.picopilot.common.model.UserRole;
import com.picopilot.identity.model.Workspace;
import com.picopilot.identity.model.WorkspaceMember;
import com.picopilot.identity.repository.WorkspaceMemberRepository;
import com.picopilot.identity.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;

    @Transactional(readOnly = true)
    public List<Workspace> listWorkspaces(String userId) {
        return memberRepository.findByUserIdAndActiveTrue(userId)
            .stream()
            .map(WorkspaceMember::getWorkspace)
            .filter(Workspace::isActive)
            .toList();
    }

    @Transactional
    public Workspace createWorkspace(Workspace workspace, String userId) {
        if (workspaceRepository.existsBySlug(workspace.getSlug())) {
            throw new ValidationException("Workspace slug already exists: " + workspace.getSlug());
        }
        if (workspaceRepository.existsByName(workspace.getName())) {
            throw new ValidationException("Workspace name already exists: " + workspace.getName());
        }
        Workspace saved = workspaceRepository.save(workspace);

        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspace(saved);
        member.setUserId(userId);
        member.setRole(UserRole.PLATFORM_ADMIN);
        member.setActive(true);
        memberRepository.save(member);

        log.info("Workspace {} created by user {}", saved.getSlug(), userId);
        return saved;
    }

    @Transactional(readOnly = true)
    public Workspace getWorkspace(UUID workspaceId, String userId) {
        requireMembership(workspaceId, userId);
        return workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new ResourceNotFoundException("Workspace", workspaceId.toString()));
    }

    @Transactional
    public Workspace updateWorkspace(UUID workspaceId, String name, String description, String userId) {
        requireRole(workspaceId, userId, UserRole.PLATFORM_ADMIN);
        Workspace workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new ResourceNotFoundException("Workspace", workspaceId.toString()));
        if (!workspace.getName().equals(name) && workspaceRepository.existsByName(name)) {
            throw new ValidationException("Workspace name already exists: " + name);
        }
        workspace.setName(name);
        workspace.setDescription(description);
        return workspaceRepository.save(workspace);
    }

    @Transactional
    public void archiveWorkspace(UUID workspaceId, String userId) {
        requireRole(workspaceId, userId, UserRole.PLATFORM_ADMIN);
        Workspace workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new ResourceNotFoundException("Workspace", workspaceId.toString()));
        workspace.setActive(false);
        workspaceRepository.save(workspace);
        log.info("Workspace {} archived by user {}", workspace.getSlug(), userId);
    }

    @Transactional
    public WorkspaceMember addMember(UUID workspaceId, String userId, String newUserId,
                                      UserRole role, String adminUserId) {
        requireRole(workspaceId, adminUserId, UserRole.PLATFORM_ADMIN);
        if (memberRepository.existsByWorkspaceIdAndUserId(workspaceId, newUserId)) {
            throw new ValidationException("User is already a member of this workspace");
        }
        Workspace workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new ResourceNotFoundException("Workspace", workspaceId.toString()));

        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspace(workspace);
        member.setUserId(newUserId);
        member.setRole(role);
        member.setActive(true);
        return memberRepository.save(member);
    }

    @Transactional
    public void removeMember(UUID workspaceId, String memberUserId, String adminUserId) {
        requireRole(workspaceId, adminUserId, UserRole.PLATFORM_ADMIN);
        WorkspaceMember member = memberRepository
            .findByWorkspaceIdAndUserId(workspaceId, memberUserId)
            .orElseThrow(() -> new ResourceNotFoundException("WorkspaceMember", memberUserId));
        member.setActive(false);
        memberRepository.save(member);
    }

    public void requireMembership(UUID workspaceId, String userId) {
        if (!memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId)) {
            throw new AccessDeniedException("User is not a member of this workspace");
        }
    }

    public void requireRole(UUID workspaceId, String userId, UserRole... allowedRoles) {
        WorkspaceMember member = memberRepository
            .findByWorkspaceIdAndUserId(workspaceId, userId)
            .orElseThrow(() -> new AccessDeniedException("User is not a member of this workspace"));

        for (UserRole role : allowedRoles) {
            if (member.getRole() == role) return;
        }
        throw new AccessDeniedException("User does not have required role: " + member.getRole());
    }

    public UserRole getUserRole(UUID workspaceId, String userId) {
        return memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
            .map(WorkspaceMember::getRole)
            .orElse(null);
    }
}