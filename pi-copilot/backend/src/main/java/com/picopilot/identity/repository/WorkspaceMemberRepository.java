package com.picopilot.identity.repository;

import com.picopilot.common.model.UserRole;
import com.picopilot.identity.model.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {
    List<WorkspaceMember> findByWorkspaceIdAndActiveTrue(UUID workspaceId);
    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(UUID workspaceId, String userId);
    List<WorkspaceMember> findByUserIdAndActiveTrue(String userId);
    boolean existsByWorkspaceIdAndUserId(UUID workspaceId, String userId);
}