package com.picopilot.project.github;

import com.picopilot.project.github.GitHubDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/github")
@RequiredArgsConstructor
public class GitHubConnectorController {

    private final GitHubConnectorService service;

    /** POST (not GET) so the token travels in the body, never in URLs or access logs. */
    @PostMapping("/repositories")
    public ConnectResponse repositories(@PathVariable UUID workspaceId,
                                        @Valid @RequestBody ConnectRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        return service.connect(workspaceId, request, jwt.getSubject());
    }

    @PostMapping("/branches")
    public List<BranchSummary> branches(@PathVariable UUID workspaceId,
                                        @Valid @RequestBody BranchesRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        return service.listBranches(workspaceId, request, jwt.getSubject());
    }

    @PostMapping("/folders")
    public FoldersResponse folders(@PathVariable UUID workspaceId,
                                   @Valid @RequestBody FoldersRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        return service.listFolders(workspaceId, request, jwt.getSubject());
    }

    @PostMapping("/connectors")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectorSummary save(@PathVariable UUID workspaceId,
                                 @Valid @RequestBody SaveConnectorRequest request,
                                 @AuthenticationPrincipal Jwt jwt) {
        return service.save(workspaceId, request, jwt.getSubject());
    }

    @GetMapping("/connectors")
    public List<ConnectorSummary> list(@PathVariable UUID workspaceId,
                                       @AuthenticationPrincipal Jwt jwt) {
        return service.list(workspaceId, jwt.getSubject());
    }

    @GetMapping("/connectors/{connectorId}")
    public ConnectorSummary get(@PathVariable UUID workspaceId,
                                @PathVariable UUID connectorId,
                                @AuthenticationPrincipal Jwt jwt) {
        return service.get(workspaceId, connectorId, jwt.getSubject());
    }

    /** Branches and folders of a saved connector, read with its stored token. */
    @GetMapping("/connectors/{connectorId}/branches")
    public List<BranchSummary> connectorBranches(@PathVariable UUID workspaceId,
                                                 @PathVariable UUID connectorId,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return service.listBranches(workspaceId, connectorId, jwt.getSubject());
    }

    @GetMapping("/connectors/{connectorId}/folders")
    public FoldersResponse connectorFolders(@PathVariable UUID workspaceId,
                                            @PathVariable UUID connectorId,
                                            @RequestParam String branch,
                                            @AuthenticationPrincipal Jwt jwt) {
        return service.listFolders(workspaceId, connectorId, branch, jwt.getSubject());
    }

    @PutMapping("/connectors/{connectorId}")
    public ConnectorSummary update(@PathVariable UUID workspaceId,
                                   @PathVariable UUID connectorId,
                                   @Valid @RequestBody UpdateConnectorRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        return service.update(workspaceId, connectorId, request, jwt.getSubject());
    }

    @DeleteMapping("/connectors/{connectorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID workspaceId,
                       @PathVariable UUID connectorId,
                       @AuthenticationPrincipal Jwt jwt) {
        service.delete(workspaceId, connectorId, jwt.getSubject());
    }
}
