package com.picopilot.monitoring.service;

import com.picopilot.audit.service.AuditService;
import com.picopilot.common.exception.InvalidStateException;
import com.picopilot.common.exception.ValidationException;
import com.picopilot.common.model.ScanStatus;
import com.picopilot.common.model.Severity;
import com.picopilot.common.model.UserRole;
import com.picopilot.common.model.WorkspaceStage;
import com.picopilot.identity.repository.WorkspaceRepository;
import com.picopilot.identity.service.WorkspaceService;
import com.picopilot.inventory.model.PiFinding;
import com.picopilot.inventory.repository.PiFindingRepository;
import com.picopilot.monitoring.model.MonitoringSchedule;
import com.picopilot.monitoring.model.MonitoringSchedule.IntervalUnit;
import com.picopilot.monitoring.repository.MonitoringScheduleRepository;
import com.picopilot.scanner.model.ScanJob;
import com.picopilot.scanner.model.ScanTarget;
import com.picopilot.scanner.repository.ScanJobRepository;
import com.picopilot.scanner.repository.ScanTargetRepository;
import com.picopilot.scanner.service.ScannerService;
import com.picopilot.scanner.service.ScannerService.ScanView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Re-scans a workspace's databases on a user-defined schedule and reports PI columns that
 * were never approved for encryption, i.e. personal data that appeared after protection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringService {

    /** Shortest allowed interval, so a schedule cannot hammer customer databases. */
    private static final int MIN_INTERVAL_MINUTES = 5;
    private static final int MAX_INTERVAL_VALUE = 1000;

    private final MonitoringScheduleRepository scheduleRepository;
    private final ScanJobRepository scanJobRepository;
    private final ScanTargetRepository targetRepository;
    private final PiFindingRepository findingRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceService workspaceService;
    private final ScannerService scannerService;
    private final AuditService auditService;

    public record ScheduleRequest(boolean enabled, int intervalValue, IntervalUnit intervalUnit,
                                  boolean valuePatternScan) {}

    public record ScheduleView(boolean enabled, int intervalValue, IntervalUnit intervalUnit,
                               boolean valuePatternScan, Instant nextRunAt, Instant lastRunAt,
                               UUID lastScanId, boolean workspaceFinalized) {}

    /**
     * @param newPiColumns PI columns found by this run that were never approved for encryption
     */
    public record RunView(UUID scanId, String name, ScanStatus status, Instant startedAt, Instant completedAt,
                          int databaseCount, Integer tablesScanned, Integer findingsCount,
                          Integer newPiColumns, String errorMessage) {}

    @Transactional(readOnly = true)
    public ScheduleView getSchedule(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return toView(scheduleRepository.findByWorkspaceId(workspaceId).orElseGet(() -> newSchedule(workspaceId)));
    }

    @Transactional
    public ScheduleView updateSchedule(UUID workspaceId, ScheduleRequest request, String userId) {
        workspaceService.requireRole(workspaceId, userId,
            UserRole.PLATFORM_ADMIN, UserRole.APP_OWNER, UserRole.SECURITY_REVIEWER);
        if (request.intervalUnit() == null) {
            throw new ValidationException("Choose an interval unit");
        }
        if (request.intervalValue() < 1 || request.intervalValue() > MAX_INTERVAL_VALUE) {
            throw new ValidationException("Interval must be between 1 and " + MAX_INTERVAL_VALUE);
        }
        if (request.intervalUnit() == IntervalUnit.MINUTES && request.intervalValue() < MIN_INTERVAL_MINUTES) {
            throw new ValidationException("The shortest interval is " + MIN_INTERVAL_MINUTES + " minutes");
        }

        MonitoringSchedule schedule = scheduleRepository.findByWorkspaceId(workspaceId)
            .orElseGet(() -> newSchedule(workspaceId));
        boolean timingChanged = !schedule.isEnabled()
            || schedule.getIntervalValue() != request.intervalValue()
            || schedule.getIntervalUnit() != request.intervalUnit();

        schedule.setEnabled(request.enabled());
        schedule.setIntervalValue(request.intervalValue());
        schedule.setIntervalUnit(request.intervalUnit());
        schedule.setValuePatternScan(request.valuePatternScan());
        if (!request.enabled()) {
            schedule.setNextRunAt(null);
        } else if (timingChanged || schedule.getNextRunAt() == null) {
            schedule.setNextRunAt(schedule.nextRunAfter(Instant.now()));
        }
        schedule = scheduleRepository.save(schedule);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("enabled", request.enabled());
        metadata.put("interval", request.intervalValue() + " " + request.intervalUnit());
        auditService.record(workspaceId, null, userId, null, "MONITORING_SCHEDULE_UPDATED", "WORKSPACE",
            workspaceId.toString(), null, "SUCCESS", Severity.INFO, null, null, metadata);
        log.info("Monitoring for workspace {} set to enabled={} every {} {} by {}", workspaceId,
            request.enabled(), request.intervalValue(), request.intervalUnit(), userId);
        return toView(schedule);
    }

    @Transactional
    public ScanView runNow(UUID workspaceId, String userId) {
        workspaceService.requireRole(workspaceId, userId,
            UserRole.PLATFORM_ADMIN, UserRole.APP_OWNER, UserRole.SECURITY_REVIEWER);
        MonitoringSchedule schedule = scheduleRepository.findByWorkspaceId(workspaceId)
            .orElseGet(() -> newSchedule(workspaceId));
        if (isRunning(schedule.getLastScanId())) {
            throw new InvalidStateException("A monitoring scan is already running");
        }
        ScanView scan = scannerService.createScheduled(workspaceId, schedule.isValuePatternScan(), userId)
            .orElseThrow(() -> new ValidationException("No verified database connector to monitor"));
        schedule.setLastRunAt(Instant.now());
        schedule.setLastScanId(scan.id());
        scheduleRepository.save(schedule);
        return scan;
    }

    @Transactional(readOnly = true)
    public List<RunView> listRuns(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        Set<String> approved = findingRepository.findByWorkspaceIdAndApprovedTrue(workspaceId).stream()
            .map(MonitoringService::columnKey)
            .collect(Collectors.toSet());
        List<ScanJob> jobs = scanJobRepository.findByWorkspaceIdAndTriggerTypeOrderByCreatedAtDesc(
            workspaceId, ScannerService.TRIGGER_SCHEDULED);
        Map<UUID, Long> databases = targetRepository.findByScanJobIdIn(jobs.stream().map(ScanJob::getId).toList())
            .stream().collect(Collectors.groupingBy(ScanTarget::getScanJobId, Collectors.counting()));
        return jobs.stream()
            .map(job -> toRun(job, databases.getOrDefault(job.getId(), 0L).intValue(), approved))
            .toList();
    }

    /** Starts every due monitoring scan. Runs once a minute by default. */
    @Scheduled(fixedDelayString = "${picopilot.monitoring.poll-interval-ms:60000}",
        initialDelayString = "${picopilot.monitoring.initial-delay-ms:30000}")
    public void runDueSchedules() {
        Instant now = Instant.now();
        for (MonitoringSchedule schedule : scheduleRepository.findByEnabledTrueAndNextRunAtLessThanEqual(now)) {
            try {
                runScheduled(schedule, now);
            } catch (Exception e) {
                log.warn("Monitoring run for workspace {} failed to start: {}", schedule.getWorkspaceId(), e.getMessage());
            }
        }
    }

    private void runScheduled(MonitoringSchedule schedule, Instant now) {
        // Schedule from now, not from the missed slot, so downtime doesn't cause a burst of scans
        schedule.setNextRunAt(schedule.nextRunAfter(now));
        if (isRunning(schedule.getLastScanId())) {
            log.info("Skipping monitoring run for workspace {}: previous scan still running", schedule.getWorkspaceId());
            scheduleRepository.save(schedule);
            return;
        }
        Optional<ScanView> scan = scannerService.createScheduled(
            schedule.getWorkspaceId(), schedule.isValuePatternScan(), ScannerService.SYSTEM_ACTOR);
        if (scan.isPresent()) {
            schedule.setLastRunAt(now);
            schedule.setLastScanId(scan.get().id());
            log.info("Monitoring scan {} started for workspace {}", scan.get().id(), schedule.getWorkspaceId());
        } else {
            log.info("Monitoring run for workspace {} skipped: no verified database", schedule.getWorkspaceId());
        }
        scheduleRepository.save(schedule);
    }

    private boolean isRunning(UUID scanId) {
        return scanId != null && scanJobRepository.findById(scanId)
            .map(j -> j.getStatus() == ScanStatus.RUNNING || j.getStatus() == ScanStatus.PENDING)
            .orElse(false);
    }

    private RunView toRun(ScanJob job, int databases, Set<String> approved) {
        Integer newPi = null;
        if (job.getStatus() == ScanStatus.COMPLETED) {
            newPi = (int) findingRepository.findByScanJobId(job.getId()).stream()
                .filter(f -> !PiFinding.ENCRYPTED.equals(f.getEncryptionState()))
                .filter(f -> !approved.contains(columnKey(f)))
                .count();
        }
        return new RunView(job.getId(), job.getName(), job.getStatus(), job.getStartedAt(), job.getCompletedAt(),
            databases, job.getTotalTablesScanned(), job.getFindingsCount(), newPi, job.getErrorMessage());
    }

    private static String columnKey(PiFinding f) {
        return f.getDatabaseConnectorId() + "|" + f.getDbSchema() + "|" + f.getTableName() + "|" + f.getColumnName();
    }

    private MonitoringSchedule newSchedule(UUID workspaceId) {
        MonitoringSchedule schedule = new MonitoringSchedule();
        schedule.setWorkspaceId(workspaceId);
        return schedule;
    }

    private ScheduleView toView(MonitoringSchedule s) {
        boolean finalized = workspaceRepository.findById(s.getWorkspaceId())
            .map(w -> w.getLifecycleStage() == WorkspaceStage.COMPLETED).orElse(false);
        return new ScheduleView(s.isEnabled(), s.getIntervalValue(), s.getIntervalUnit(), s.isValuePatternScan(),
            s.getNextRunAt(), s.getLastRunAt(), s.getLastScanId(), finalized);
    }
}
