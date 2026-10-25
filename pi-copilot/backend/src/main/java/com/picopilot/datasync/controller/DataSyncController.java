package com.picopilot.datasync.controller;

import com.picopilot.datasync.service.DataSyncService;
import com.picopilot.datasync.service.DataSyncService.Overview;
import com.picopilot.datasync.service.DataSyncService.RowsPage;
import com.picopilot.datasync.service.DataSyncService.RunView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/data-sync")
@RequiredArgsConstructor
public class DataSyncController {

    private final DataSyncService dataSyncService;

    /** What changes in each table, current progress and past runs. */
    @GetMapping
    public Overview overview(@PathVariable UUID workspaceId, @AuthenticationPrincipal Jwt jwt) {
        return dataSyncService.overview(workspaceId, jwt.getSubject());
    }

    /** A page of one table's rows (masked) for the row viewer: filter ALL, ENCRYPTED or PENDING. */
    @GetMapping("/rows")
    public RowsPage rows(@PathVariable UUID workspaceId, @RequestParam UUID connectorId, @RequestParam String schema,
                         @RequestParam String table, @RequestParam(defaultValue = "ALL") String filter,
                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
                         @AuthenticationPrincipal Jwt jwt) {
        return dataSyncService.rows(workspaceId, connectorId, schema, table, filter, page, size, jwt.getSubject());
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public RunView start(@PathVariable UUID workspaceId, @AuthenticationPrincipal Jwt jwt) {
        return dataSyncService.start(workspaceId, jwt.getSubject());
    }
}
