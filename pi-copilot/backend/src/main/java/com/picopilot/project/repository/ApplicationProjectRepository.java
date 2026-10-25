package com.picopilot.project.repository;

import com.picopilot.project.model.ApplicationProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ApplicationProjectRepository extends JpaRepository<ApplicationProject, UUID> {
    List<ApplicationProject> findByWorkspaceId(UUID workspaceId);
}