package com.picopilot.monitoring.controller;

import com.picopilot.monitoring.service.MonitoringService;
import com.picopilot.monitoring.service.MonitoringService.RunView;
import com.picopilot.monitoring.service.MonitoringService.ScheduleRequest;
import com.picopilot.monitoring.service.MonitoringService.ScheduleView;
import com.picopilot.scanner.service.ScannerService.ScanView;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final MonitoringService monitoringService;

    @GetMapping
    public ScheduleView getSchedule(@PathVariable UUID workspaceId,
                                    @AuthenticationPrincipal Jwt jwt) {
        return monitoringService.getSchedule(workspaceId, jwt.getSubject());
    }

    @PutMapping
    public ScheduleView updateSchedule(@PathVariable UUID workspaceId,
                                       @RequestBody ScheduleRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        return monitoringService.updateSchedule(workspaceId, request, jwt.getSubject());
    }

    @PostMapping("/run")
    public ScanView runNow(@PathVariable UUID workspaceId,
                           @AuthenticationPrincipal Jwt jwt) {
        return monitoringService.runNow(workspaceId, jwt.getSubject());
    }

    @GetMapping("/runs")
    public List<RunView> listRuns(@PathVariable UUID workspaceId,
                                  @AuthenticationPrincipal Jwt jwt) {
        return monitoringService.listRuns(workspaceId, jwt.getSubject());
    }
}
