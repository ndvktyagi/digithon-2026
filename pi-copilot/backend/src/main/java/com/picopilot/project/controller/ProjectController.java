package com.picopilot.project.controller;

import com.picopilot.project.model.ApplicationProject;
import com.picopilot.project.model.DatabaseConnector;
import com.picopilot.project.model.RepositoryConnector;
import com.picopilot.project.service.ProjectService;
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
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping("/workspaces/{workspaceId}/projects")
    public List<ApplicationProject> listProjects(@PathVariable UUID workspaceId,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return projectService.listProjects(workspaceId, jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationProject createProject(@PathVariable UUID workspaceId,
                                             @Valid @RequestBody ApplicationProject project,
                                             @AuthenticationPrincipal Jwt jwt) {
        return projectService.createProject(workspaceId, project, jwt.getSubject());
    }

    @GetMapping("/workspaces/{workspaceId}/projects/{projectId}")
    public ApplicationProject getProject(@PathVariable UUID workspaceId,
                                          @PathVariable UUID projectId,
                                          @AuthenticationPrincipal Jwt jwt) {
        return projectService.getProject(workspaceId, projectId, jwt.getSubject());
    }

    // Repository Connectors
    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/repo-connectors")
    @ResponseStatus(HttpStatus.CREATED)
    public RepositoryConnector createRepoConnector(@PathVariable UUID workspaceId,
                                                     @PathVariable UUID projectId,
                                                     @Valid @RequestBody RepositoryConnector connector,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return projectService.createRepoConnector(workspaceId, projectId, connector, jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/connectors/repo/{connectorId}/test")
    public RepositoryConnector testRepoConnector(@PathVariable UUID workspaceId,
                                                   @PathVariable UUID connectorId,
                                                   @AuthenticationPrincipal Jwt jwt) {
        return projectService.testRepoConnector(workspaceId, connectorId, jwt.getSubject());
    }

    // Database Connectors
    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/db-connectors")
    @ResponseStatus(HttpStatus.CREATED)
    public DatabaseConnector createDbConnector(@PathVariable UUID workspaceId,
                                                @PathVariable UUID projectId,
                                                @Valid @RequestBody DatabaseConnector connector,
                                                @AuthenticationPrincipal Jwt jwt) {
        return projectService.createDbConnector(workspaceId, projectId, connector, jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/connectors/db/{connectorId}/test")
    public DatabaseConnector testDbConnector(@PathVariable UUID workspaceId,
                                               @PathVariable UUID connectorId,
                                               @AuthenticationPrincipal Jwt jwt) {
        return projectService.testDbConnector(workspaceId, connectorId, jwt.getSubject());
    }
}