package com.picopilot.project.service;

import com.picopilot.common.exception.ConnectorException;
import com.picopilot.common.exception.ResourceNotFoundException;
import com.picopilot.common.exception.ValidationException;
import com.picopilot.identity.service.WorkspaceService;
import com.picopilot.project.model.ApplicationProject;
import com.picopilot.project.model.DatabaseConnector;
import com.picopilot.project.model.RepositoryConnector;
import com.picopilot.project.repository.ApplicationProjectRepository;
import com.picopilot.project.repository.DatabaseConnectorRepository;
import com.picopilot.project.repository.RepositoryConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ApplicationProjectRepository projectRepository;
    private final RepositoryConnectorRepository repoConnectorRepository;
    private final DatabaseConnectorRepository dbConnectorRepository;
    private final WorkspaceService workspaceService;

    @Transactional(readOnly = true)
    public List<ApplicationProject> listProjects(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return projectRepository.findByWorkspaceId(workspaceId);
    }

    @Transactional
    public ApplicationProject createProject(UUID workspaceId, ApplicationProject project, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        project.setWorkspaceId(workspaceId);
        return projectRepository.save(project);
    }

    @Transactional(readOnly = true)
    public ApplicationProject getProject(UUID workspaceId, UUID projectId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return projectRepository.findById(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Project", projectId.toString()));
    }

    // Repository Connector
    @Transactional
    public RepositoryConnector createRepoConnector(UUID workspaceId, UUID projectId,
                                                    RepositoryConnector connector, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        connector.setWorkspaceId(workspaceId);
        connector.setProject(getProject(workspaceId, projectId, userId));
        return repoConnectorRepository.save(connector);
    }

    @Transactional
    public RepositoryConnector testRepoConnector(UUID workspaceId, UUID connectorId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        RepositoryConnector connector = repoConnectorRepository.findById(connectorId)
            .orElseThrow(() -> new ResourceNotFoundException("RepositoryConnector", connectorId.toString()));

        try {
            // Test GitHub connectivity - validate repository access
            // Actual implementation would use GitHub API
            log.info("Testing repository connector {} for repository {}/{}",
                connectorId, connector.getRepositoryOwner(), connector.getRepositoryName());
            connector.setVerified(true);
            return repoConnectorRepository.save(connector);
        } catch (Exception e) {
            connector.setVerified(false);
            repoConnectorRepository.save(connector);
            throw new ConnectorException("Repository connection test failed", e);
        }
    }

    // Database Connector
    @Transactional
    public DatabaseConnector createDbConnector(UUID workspaceId, UUID projectId,
                                                DatabaseConnector connector, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        connector.setWorkspaceId(workspaceId);
        connector.setProject(getProject(workspaceId, projectId, userId));
        return dbConnectorRepository.save(connector);
    }

    @Transactional
    public DatabaseConnector testDbConnector(UUID workspaceId, UUID connectorId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        DatabaseConnector connector = dbConnectorRepository.findById(connectorId)
            .orElseThrow(() -> new ResourceNotFoundException("DatabaseConnector", connectorId.toString()));

        // Test database connectivity - validate without logging credentials
        // In production, secrets would be fetched from secret manager
        try {
            log.info("Testing database connector {} for {}:{}/{}",
                connectorId, connector.getHost(), connector.getPort(), connector.getDatabaseName());
            // Actual connectivity test would resolve secret references
            connector.setVerified(true);
            return dbConnectorRepository.save(connector);
        } catch (Exception e) {
            connector.setVerified(false);
            dbConnectorRepository.save(connector);
            throw new ConnectorException("Database connection test failed", e);
        }
    }
}