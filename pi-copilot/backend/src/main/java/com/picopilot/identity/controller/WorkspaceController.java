package com.picopilot.identity.controller;

import com.picopilot.identity.dto.AddMemberRequest;
import com.picopilot.identity.dto.AdvanceStageRequest;
import com.picopilot.identity.dto.CreateWorkspaceRequest;
import com.picopilot.identity.dto.ReopenStageRequest;
import com.picopilot.identity.dto.UpdateWorkspaceRequest;
import com.picopilot.identity.dto.WorkspaceLifecycleResponse;
import com.picopilot.identity.dto.WorkspaceResponse;
import com.picopilot.identity.model.Workspace;
import com.picopilot.identity.model.WorkspaceMember;
import com.picopilot.identity.service.WorkspaceLifecycleService;
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
    private final WorkspaceLifecycleService lifecycleService;

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

    @PutMapping("/{workspaceId}")
    public WorkspaceResponse updateWorkspace(@PathVariable UUID workspaceId,
                                              @Valid @RequestBody UpdateWorkspaceRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        return toResponse(workspaceService.updateWorkspace(
            workspaceId, request.name(), request.description(), jwt.getSubject()));
    }

    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveWorkspace(@PathVariable UUID workspaceId,
                                 @AuthenticationPrincipal Jwt jwt) {
        workspaceService.archiveWorkspace(workspaceId, jwt.getSubject());
    }

    @GetMapping("/{workspaceId}/lifecycle")
    public WorkspaceLifecycleResponse getLifecycle(@PathVariable UUID workspaceId,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return lifecycleService.getLifecycle(workspaceId, jwt.getSubject());
    }

    @PostMapping("/{workspaceId}/lifecycle/advance")
    public WorkspaceLifecycleResponse advanceStage(@PathVariable UUID workspaceId,
                                                    @RequestBody(required = false) AdvanceStageRequest request,
                                                    @AuthenticationPrincipal Jwt jwt) {
        boolean confirmed = request != null && request.confirmed();
        return lifecycleService.advance(workspaceId, jwt.getSubject(), confirmed);
    }

    @PostMapping("/{workspaceId}/lifecycle/reopen")
    public WorkspaceLifecycleResponse reopenStage(@PathVariable UUID workspaceId,
                                                   @Valid @RequestBody ReopenStageRequest request,
                                                   @AuthenticationPrincipal Jwt jwt) {
        return lifecycleService.reopen(workspaceId, jwt.getSubject(), request.stage());
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
            w.getDescription(), w.isActive(), w.getLifecycleStage(),
            w.getCreatedAt(), w.getUpdatedAt()
        );
    }
}