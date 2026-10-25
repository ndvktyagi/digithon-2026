package com.picopilot.featureflag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picopilot.audit.service.AuditService;
import com.picopilot.common.event.StageWorkCompletedEvent;
import com.picopilot.common.exception.InvalidStateException;
import com.picopilot.common.exception.ValidationException;
import com.picopilot.common.model.Severity;
import com.picopilot.common.model.UserRole;
import com.picopilot.common.model.WorkspaceStage;
import com.picopilot.datasync.model.DataSyncRun;
import com.picopilot.datasync.repository.DataSyncRunRepository;
import com.picopilot.featureflag.model.FeatureFlag;
import com.picopilot.featureflag.repository.FeatureFlagRepository;
import com.picopilot.identity.repository.WorkspaceRepository;
import com.picopilot.identity.service.WorkspaceService;
import com.picopilot.implementation.model.ImplementationRun;
import com.picopilot.implementation.repository.ImplementationRunRepository;
import com.picopilot.implementation.service.EncryptionCodeTemplates;
import com.picopilot.implementation.service.NativeQueryEncryptor;
import com.picopilot.project.database.DatabaseConnectorService;
import com.picopilot.project.model.DatabaseConnector;
import com.picopilot.project.repository.DatabaseConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The "Enable Encryption" switch: turns encrypted reads on or off in the protected application by
 * writing the "encrypted-read" row of {@code pi_feature_flag} in each target database. The code generated
 * in Implementation (PiEncryptionFlags + an entity @PostLoad hook) polls that row, so the application
 * switches within seconds without a redeploy. A FeatureFlag record mirrors the state for the lifecycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EncryptionSwitchService {

    private static final String FLAG_TABLE = EncryptionCodeTemplates.FLAG_TABLE;
    private static final String FLAG = EncryptionCodeTemplates.ENCRYPTED_READ_FLAG;
    private static final String ENCRYPTED_READ = "ENCRYPTED_READ";
    private static final String LEGACY_READ = "LEGACY_READ";
    private static final String NATIVE_UNSUPPORTED = NativeQueryEncryptor.UNSUPPORTED;

    private final FeatureFlagRepository flagRepository;
    private final ImplementationRunRepository implementationRunRepository;
    private final DataSyncRunRepository dataSyncRunRepository;
    private final DatabaseConnectorRepository dbConnectorRepository;
    private final DatabaseConnectorService dbConnectorService;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    /** Live state of the switch in one target database ({@code enabled} is null if it cannot be read). */
    public record DatabaseSwitch(UUID connectorId, String database, Boolean enabled, String error) {}

    public record Status(boolean enabled, WorkspaceStage stage, String branchName, Integer prNumber, String prUrl,
                         List<String> tables, List<DatabaseSwitch> databases, boolean canEnable,
                         List<String> problems, List<String> readGaps, Instant enabledAt, String enabledBy,
                         Instant disabledAt, String disabledBy, String disableReason) {}

    @Transactional(readOnly = true)
    public Status status(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return buildStatus(workspaceId);
    }

    @Transactional
    public Status setEnabled(UUID workspaceId, boolean enabled, String reason, String userId) {
        workspaceService.requireRole(workspaceId, userId, UserRole.PLATFORM_ADMIN, UserRole.APP_OWNER);
        ImplementationRun impl = approvedImplementation(workspaceId);
        if (impl == null) {
            throw new ValidationException("Approve an implementation in Final Review first");
        }
        if (enabled) {
            List<String> problems = enableProblems(workspaceId);
            if (!problems.isEmpty()) {
                throw new InvalidStateException(String.join("; ", problems));
            }
        }
        List<DatabaseConnector> connectors = connectors(workspaceId, impl);
        if (connectors.isEmpty()) {
            throw new ValidationException("The approved implementation has no database to switch");
        }
        for (DatabaseConnector connector : connectors) {
            writeSwitch(connector, enabled);
        }

        FeatureFlag flag = flagRepository.findByFlagName(flagName(workspaceId)).orElseGet(() -> newFlag(workspaceId, connectors.get(0)));
        flag.setTargetState(enabled ? ENCRYPTED_READ : LEGACY_READ);
        flag.setCurrentState(enabled ? ENCRYPTED_READ : LEGACY_READ);
        if (enabled) {
            flag.setEnabledAt(Instant.now());
            flag.setEnabledBy(userId);
        } else {
            flag.setRollbackAt(Instant.now());
            flag.setRollbackBy(userId);
            flag.setRollbackReason(reason == null || reason.isBlank() ? null : reason.trim());
        }
        flagRepository.save(flag);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("databases", connectors.size());
        if (!enabled && reason != null && !reason.isBlank()) metadata.put("reason", reason.trim());
        auditService.record(workspaceId, null, userId, null,
            enabled ? "ENCRYPTED_READ_ENABLED" : "ENCRYPTED_READ_DISABLED", "FEATURE_FLAG", flag.getId().toString(),
            flag.getFlagName(), "SUCCESS", enabled ? Severity.INFO : Severity.MEDIUM, null, null, metadata);
        log.info("Encrypted read {} for workspace {} by {}", enabled ? "enabled" : "disabled", workspaceId, userId);
        if (enabled) {
            events.publishEvent(new StageWorkCompletedEvent(workspaceId, WorkspaceStage.ENABLE_ENCRYPTION, userId));
        }
        return buildStatus(workspaceId);
    }

    private Status buildStatus(UUID workspaceId) {
        WorkspaceStage stage = workspaceRepository.findById(workspaceId).orElseThrow().getLifecycleStage();
        ImplementationRun impl = approvedImplementation(workspaceId);
        List<DatabaseSwitch> databases = new ArrayList<>();
        Set<String> tables = new LinkedHashSet<>();
        List<String> readGaps = new ArrayList<>();
        if (impl != null) {
            for (Map<String, Object> c : columns(impl)) {
                tables.add(c.get("schema") + "." + c.get("table"));
                String column = c.get("table") + "." + c.get("column");
                Object read = c.get("readStatus");
                Object code = c.get("codeStatus");
                if (read == null) {
                    readGaps.add("The approved code predates the encrypted-read switch; run a new Implementation round");
                    continue;
                }
                // Only known failures count, so new success statuses from the generator are not reported as gaps
                String codeStatus = String.valueOf(code);
                if (code == null || codeStatus.equals("NO_ENTITY") || codeStatus.equals("NO_FIELD")
                    || codeStatus.startsWith("UNSUPPORTED_TYPE") || codeStatus.equals("FAILED")) {
                    readGaps.add(column + ": the code could not be updated (" + code + "), so it keeps reading the plain column");
                } else if (NATIVE_UNSUPPORTED.equals(read)) {
                    readGaps.add(column + ": a native SQL query could not be rewritten and keeps reading the plain column");
                }
            }
            for (DatabaseConnector connector : connectors(workspaceId, impl)) {
                databases.add(readSwitch(connector));
            }
        }
        FeatureFlag flag = flagRepository.findByFlagName(flagName(workspaceId)).orElse(null);
        boolean enabled = !databases.isEmpty() && databases.stream().allMatch(d -> Boolean.TRUE.equals(d.enabled()));
        List<String> problems = enableProblems(workspaceId);
        return new Status(enabled, stage,
            impl == null ? null : impl.getBranchName(), impl == null ? null : impl.getPrNumber(),
            impl == null ? null : impl.getPrUrl(), List.copyOf(tables), databases, problems.isEmpty(), problems,
            List.copyOf(new LinkedHashSet<>(readGaps)),
            flag == null ? null : flag.getEnabledAt(), flag == null ? null : flag.getEnabledBy(),
            flag == null ? null : flag.getRollbackAt(), flag == null ? null : flag.getRollbackBy(),
            flag == null ? null : flag.getRollbackReason());
    }

    /** Encrypted reads are only safe once every row has its encrypted copy. */
    private List<String> enableProblems(UUID workspaceId) {
        List<String> problems = new ArrayList<>();
        WorkspaceStage stage = workspaceRepository.findById(workspaceId).orElseThrow().getLifecycleStage();
        if (stage.ordinal() < WorkspaceStage.ENABLE_ENCRYPTION.ordinal() || stage == WorkspaceStage.REJECTED) {
            problems.add("Encryption can be enabled at the Enable Encryption stage (now: " + stage + ")");
        }
        ImplementationRun impl = approvedImplementation(workspaceId);
        if (impl == null) {
            problems.add("Approve an implementation in Final Review first");
        } else if (!ImplementationRun.MERGED.equals(impl.getMergeStatus())) {
            // The application only has the flag-aware code once the approved pull request is merged
            problems.add("Merge the approved pull request into " + impl.getBaseBranch()
                + (impl.getMergeMessage() != null ? " (" + impl.getMergeMessage() + ")" : "")
                + "; retry the merge in Final Review");
        }
        // Nothing writes the encrypted columns while the switch is off, so they are only current
        // if the latest sync completed for the code that is approved now
        DataSyncRun sync = dataSyncRunRepository.findFirstByWorkspaceIdOrderByCreatedAtDesc(workspaceId).orElse(null);
        if (sync == null || !DataSyncRun.COMPLETED.equals(sync.getStatus())) {
            problems.add("Complete Data Sync so every row has its encrypted value");
        } else if (impl != null && !impl.getId().equals(sync.getImplementationRunId())) {
            problems.add("Run Data Sync again for the approved round " + impl.getRoundNumber()
                + " (the last sync was for an earlier round)");
        }
        return problems;
    }

    private DatabaseSwitch readSwitch(DatabaseConnector connector) {
        String label = label(connector);
        try (Connection conn = dbConnectorService.openConnection(connector);
             PreparedStatement st = conn.prepareStatement("SELECT enabled FROM " + FLAG_TABLE + " WHERE flag_name = ?")) {
            st.setQueryTimeout(15);
            st.setString(1, FLAG);
            try (ResultSet rs = st.executeQuery()) {
                return new DatabaseSwitch(connector.getId(), label, rs.next() && rs.getBoolean(1), null);
            }
        } catch (SQLException e) {
            // 42P01: table not created yet, i.e. never enabled
            if ("42P01".equals(e.getSQLState())) return new DatabaseSwitch(connector.getId(), label, false, null);
            return new DatabaseSwitch(connector.getId(), label, null, firstLine(e.getMessage()));
        }
    }

    private void writeSwitch(DatabaseConnector connector, boolean enabled) {
        String label = label(connector);
        try (Connection conn = dbConnectorService.openWriteConnection(connector)) {
            try (Statement ddl = conn.createStatement();
                 PreparedStatement st = conn.prepareStatement("INSERT INTO " + FLAG_TABLE + " (flag_name, enabled, updated_at)"
                     + " VALUES (?, ?, NOW()) ON CONFLICT (flag_name) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = NOW()")) {
                ddl.setQueryTimeout(30);
                ddl.execute(EncryptionCodeTemplates.FLAG_TABLE_DDL);
                st.setQueryTimeout(30);
                st.setString(1, FLAG);
                st.setBoolean(2, enabled);
                st.executeUpdate();
                if (!conn.getAutoCommit()) conn.commit();
            } catch (SQLException e) {
                if (!conn.getAutoCommit()) conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new InvalidStateException("Could not update the switch in " + label + ": " + firstLine(e.getMessage()));
        }
    }

    /** The most recent implementation round approved in Final Review. */
    private ImplementationRun approvedImplementation(UUID workspaceId) {
        return implementationRunRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
            .filter(r -> ImplementationRun.APPROVED.equals(r.getReviewStatus()))
            .findFirst().orElse(null);
    }

    private List<Map<String, Object>> columns(ImplementationRun impl) {
        try {
            return impl.getColumnsJson() == null ? List.of()
                : objectMapper.readValue(impl.getColumnsJson(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("The approved implementation has unreadable column data", e);
        }
    }

    /** Target databases of the approved implementation, matched by connector id or, for old runs, by label. */
    private List<DatabaseConnector> connectors(UUID workspaceId, ImplementationRun impl) {
        List<DatabaseConnector> all = dbConnectorRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
        Map<UUID, DatabaseConnector> result = new LinkedHashMap<>();
        for (Map<String, Object> c : columns(impl)) {
            Object id = c.get("connectorId");
            String database = String.valueOf(c.get("database"));
            all.stream()
                .filter(dc -> id != null ? dc.getId().toString().equals(String.valueOf(id)) : label(dc).equals(database))
                .findFirst().ifPresent(dc -> result.putIfAbsent(dc.getId(), dc));
        }
        return List.copyOf(result.values());
    }

    private FeatureFlag newFlag(UUID workspaceId, DatabaseConnector connector) {
        FeatureFlag flag = new FeatureFlag();
        flag.setWorkspaceId(workspaceId);
        flag.setProjectId(connector.getProject().getId());
        flag.setFlagName(flagName(workspaceId));
        flag.setDescription("Application reads PI values from the encrypted columns");
        flag.setScope("APPLICATION");
        flag.setScopeTarget(FLAG);
        flag.setCurrentState("DISABLED");
        flag.setApprovalRequired(false);
        return flag;
    }

    private static String flagName(UUID workspaceId) {
        return FLAG + ":" + workspaceId;
    }

    private static String label(DatabaseConnector c) {
        return c.getHost() + ":" + c.getPort() + "/" + c.getDatabaseName();
    }

    private static String firstLine(String message) {
        return message == null ? "unknown error" : message.lines().findFirst().orElse(message);
    }
}
