package com.picopilot.project.database;

import com.picopilot.project.database.DatabaseConnectorService.ConnectionRequest;
import com.picopilot.project.database.DatabaseConnectorService.ConnectorSummary;
import com.picopilot.project.database.DatabaseConnectorService.TestResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/database")
@RequiredArgsConstructor
public class DatabaseConnectorController {

    private final DatabaseConnectorService service;

    @PostMapping("/test")
    public TestResult test(@PathVariable UUID workspaceId,
                           @Valid @RequestBody ConnectionRequest request,
                           @AuthenticationPrincipal Jwt jwt) {
        return service.test(workspaceId, request, jwt.getSubject());
    }

    @PostMapping("/connectors")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectorSummary save(@PathVariable UUID workspaceId,
                                 @Valid @RequestBody ConnectionRequest request,
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

    @PostMapping("/connectors/{connectorId}/test")
    public TestResult testExisting(@PathVariable UUID workspaceId,
                                   @PathVariable UUID connectorId,
                                   @Valid @RequestBody ConnectionRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        return service.testExisting(workspaceId, connectorId, request, jwt.getSubject());
    }

    @PutMapping("/connectors/{connectorId}")
    public ConnectorSummary update(@PathVariable UUID workspaceId,
                                   @PathVariable UUID connectorId,
                                   @Valid @RequestBody ConnectionRequest request,
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
