package com.picopilot.identity.controller;

import com.picopilot.identity.dto.AddMemberRequest;
import com.picopilot.identity.dto.CreateWorkspaceRequest;
import com.picopilot.identity.dto.WorkspaceResponse;
import com.picopilot.identity.model.Workspace;
import com.picopilot.identity.model.WorkspaceMember;
import com.picopilot.identity.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @GetMapping
    public List<WorkspaceResponse> listWorkspaces(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        return workspaceService.listWorkspaces(userId).stream()
            .map(this::toResponse)
            .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse createWorkspace(@Valid @RequestBody CreateWorkspaceRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        Workspace workspace = new Workspace();
        workspace.setName(request.name());
        workspace.setSlug(request.slug());
        workspace.setDescription(request.description());
        return toResponse(workspaceService.createWorkspace(workspace, jwt.getSubject()));
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceResponse getWorkspace(@PathVariable UUID workspaceId,
                                           @AuthenticationPrincipal Jwt jwt) {
        return toResponse(workspaceService.getWorkspace(workspaceId, jwt.getSubject()));
    }

    @PostMapping("/{workspaceId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public void addMember(@PathVariable UUID workspaceId,
                          @Valid @RequestBody AddMemberRequest request,
                          @AuthenticationPrincipal Jwt jwt) {
        workspaceService.addMember(workspaceId, jwt.getSubject(),
            request.userId(), request.role(), jwt.getSubject());
    }

    @DeleteMapping("/{workspaceId}/members/{memberUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID workspaceId,
                              @PathVariable String memberUserId,
                              @AuthenticationPrincipal Jwt jwt) {
        workspaceService.removeMember(workspaceId, memberUserId, jwt.getSubject());
    }

    private WorkspaceResponse toResponse(Workspace w) {
        return new WorkspaceResponse(
            w.getId(), w.getName(), w.getSlug(),
            w.getDescription(), w.isActive(),
            w.getCreatedAt(), w.getUpdatedAt()
        );
    }
}