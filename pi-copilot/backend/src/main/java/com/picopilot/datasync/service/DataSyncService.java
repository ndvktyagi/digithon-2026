package com.picopilot.datasync.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picopilot.audit.service.AuditService;
import com.picopilot.common.exception.InvalidStateException;
import com.picopilot.common.exception.ValidationException;
import com.picopilot.common.model.Severity;
import com.picopilot.common.model.UserRole;
import com.picopilot.common.model.WorkspaceStage;
import com.picopilot.datasync.model.DataSyncRun;
import com.picopilot.datasync.repository.DataSyncRunRepository;
import com.picopilot.encryption.local.LocalFieldCipher;
import com.picopilot.identity.repository.WorkspaceRepository;
import com.picopilot.identity.service.WorkspaceService;
import com.picopilot.implementation.model.ImplementationRun;
import com.picopilot.implementation.repository.ImplementationRunRepository;
import com.picopilot.project.database.DatabaseConnectorService;
import com.picopilot.project.model.DatabaseConnector;
import com.picopilot.project.repository.DatabaseConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Data Sync: shows, table by table, what the encryption changes in the database and runs the
 * job that fills the encrypted columns from the plain ones.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSyncService {

    private static final int MAX_PAGE_SIZE = 100;

    private final DataSyncRunRepository runRepository;
    private final ImplementationRunRepository implementationRunRepository;
    private final DatabaseConnectorRepository dbConnectorRepository;
    private final DatabaseConnectorService dbConnectorService;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final DataSyncExecutor executor;
    private final ObjectMapper objectMapper;
    private final LocalFieldCipher cipher;

    /** One table to sync and the plain columns in it that have an encrypted twin. */
    public record TablePlan(UUID connectorId, String database, String schema, String table, List<String> columns) {}

    /** Per column: rows with a value, rows whose value is encrypted, rows still pending. */
    public record ColumnStat(String column, String encryptedColumn, long values, long encrypted, long pending) {}

    /**
     * @param rowsWithPi    rows with at least one PI value
     * @param encryptedRows rows with PI whose values are all encrypted
     * @param pendingRows   rows with at least one PI value not encrypted yet
     */
    public record TableView(UUID connectorId, String database, String schema, String table, long rows,
                            long rowsWithPi, long encryptedRows, long pendingRows,
                            List<ColumnStat> columns, String error) {}

    /** One value in the row viewer: the plain value masked, the start of its ciphertext, and its state. */
    public record CellView(String column, String plainMasked, String encryptedPreview, String state) {}

    public record RowsPage(String schema, String table, String filter, long total, int page, int size,
                           List<String> columns, List<List<CellView>> rows) {}

    public record RunView(UUID id, String status, String phase, long rowsEncrypted, long valuesEncrypted,
                          long valuesVerified, long verifyFailures, long rowsVerified, long rowsFailed,
                          List<Map<String, Object>> tables,
                          List<String> log, Instant createdAt, Instant startedAt, Instant completedAt,
                          String errorMessage) {}

    public record Overview(UUID implementationRunId, String branchName, Integer prNumber, String prUrl,
                           WorkspaceStage stage, boolean canStart, List<String> problems, List<TableView> tables,
                           long totalRows, long encryptedRows, long pendingRows, List<RunView> runs) {}

    @Transactional(readOnly = true)
    public Overview overview(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        WorkspaceStage stage = workspaceRepository.findById(workspaceId).orElseThrow().getLifecycleStage();
        ImplementationRun impl = approvedImplementation(workspaceId);
        List<String> problems = new ArrayList<>();
        List<TableView> tables = new ArrayList<>();
        if (impl == null) {
            problems.add("Approve an implementation in Final Review first.");
        } else {
            for (TablePlan plan : plan(workspaceId, impl)) {
                tables.add(inspect(plan));
            }
        }
        if (stage != WorkspaceStage.DATA_SYNC) {
            problems.add("Data Sync runs when the workspace is at the Data Sync stage (now: " + stage + ").");
        }
        boolean running = runRepository.existsByWorkspaceIdAndStatusIn(workspaceId,
            List.of(DataSyncRun.PENDING, DataSyncRun.RUNNING));
        long total = 0, encrypted = 0, pending = 0;
        for (TableView t : tables) {
            total += t.rows();
            encrypted += t.encryptedRows();
            pending += t.pendingRows();
            if (t.error() != null) problems.add(t.schema() + "." + t.table() + ": " + t.error());
        }
        List<RunView> runs = runRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream().map(this::toView).toList();
        return new Overview(impl == null ? null : impl.getId(), impl == null ? null : impl.getBranchName(),
            impl == null ? null : impl.getPrNumber(), impl == null ? null : impl.getPrUrl(), stage,
            problems.isEmpty() && !running, problems, tables, total, encrypted, pending, runs);
    }

    @Transactional
    public RunView start(UUID workspaceId, String userId) {
        workspaceService.requireRole(workspaceId, userId, UserRole.PLATFORM_ADMIN, UserRole.APP_OWNER);
        WorkspaceStage stage = workspaceRepository.findById(workspaceId).orElseThrow().getLifecycleStage();
        if (stage != WorkspaceStage.DATA_SYNC) {
            throw new InvalidStateException("The workspace is not at the Data Sync stage (now: " + stage + ")");
        }
        ImplementationRun impl = approvedImplementation(workspaceId);
        if (impl == null) {
            throw new ValidationException("Approve an implementation in Final Review first");
        }
        if (runRepository.existsByWorkspaceIdAndStatusIn(workspaceId, List.of(DataSyncRun.PENDING, DataSyncRun.RUNNING))) {
            throw new InvalidStateException("A data sync is already running");
        }
        DataSyncRun run = new DataSyncRun();
        run.setWorkspaceId(workspaceId);
        run.setImplementationRunId(impl.getId());
        run = runRepository.save(run);
        List<TablePlan> plans = plan(workspaceId, impl);

        UUID runId = run.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.execute(runId, plans);
            }
        });
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tables", plans.size());
        auditService.record(workspaceId, null, userId, null, "DATA_SYNC_STARTED", "DATA_SYNC", runId.toString(),
            impl.getBranchName(), "SUCCESS", Severity.INFO, null, null, metadata);
        return toView(run);
    }

    /** The most recent implementation round approved in Final Review. */
    private ImplementationRun approvedImplementation(UUID workspaceId) {
        return implementationRunRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
            .filter(r -> ImplementationRun.APPROVED.equals(r.getReviewStatus()))
            .findFirst().orElse(null);
    }

    /** Tables and columns from the approved implementation, grouped per database table. */
    List<TablePlan> plan(UUID workspaceId, ImplementationRun impl) {
        List<Map<String, Object>> columns;
        try {
            columns = impl.getColumnsJson() == null ? List.of()
                : objectMapper.readValue(impl.getColumnsJson(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("The approved implementation has unreadable column data", e);
        }
        List<DatabaseConnector> connectors = dbConnectorRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
        Map<String, TablePlan> plans = new LinkedHashMap<>();
        for (Map<String, Object> c : columns) {
            String database = String.valueOf(c.get("database"));
            UUID connectorId = c.get("connectorId") != null ? UUID.fromString(String.valueOf(c.get("connectorId")))
                // Runs from before connector ids were recorded only kept the "host:port/db" label
                : connectors.stream().filter(dc -> label(dc).equals(database)).map(DatabaseConnector::getId)
                    .findFirst().orElse(null);
            String schema = String.valueOf(c.get("schema"));
            String table = String.valueOf(c.get("table"));
            TablePlan plan = plans.computeIfAbsent(connectorId + "|" + schema + "|" + table,
                k -> new TablePlan(connectorId, database, schema, table, new ArrayList<>()));
            plan.columns().add(String.valueOf(c.get("column")));
        }
        return List.copyOf(plans.values());
    }

    private TableView inspect(TablePlan plan) {
        DatabaseConnector connector = plan.connectorId() == null ? null
            : dbConnectorRepository.findById(plan.connectorId()).orElse(null);
        if (connector == null) {
            return new TableView(plan.connectorId(), plan.database(), plan.schema(), plan.table(), 0, 0, 0, 0, List.of(),
                "The database connector no longer exists");
        }
        String table = quote(plan.schema()) + "." + quote(plan.table());
        StringBuilder counts = new StringBuilder("SELECT count(*), count(*) FILTER (WHERE ")
            .append(anyValue(plan)).append("), count(*) FILTER (WHERE ").append(anyPending(plan)).append(")");
        for (String col : plan.columns()) {
            String p = quote(col), e = quote(col + "_enc");
            counts.append(", count(").append(p).append("), count(*) FILTER (WHERE ").append(p).append(" IS NOT NULL AND ")
                .append(e).append(" IS NOT NULL), count(*) FILTER (WHERE ").append(p).append(" IS NOT NULL AND ")
                .append(e).append(" IS NULL)");
        }
        counts.append(" FROM ").append(table);

        try (Connection conn = dbConnectorService.openConnection(connector); Statement st = conn.createStatement()) {
            st.setQueryTimeout(60);
            try (ResultSet rs = st.executeQuery(counts.toString())) {
                rs.next();
                long rows = rs.getLong(1), withPi = rs.getLong(2), pending = rs.getLong(3);
                List<ColumnStat> stats = new ArrayList<>();
                for (int i = 0; i < plan.columns().size(); i++) {
                    String col = plan.columns().get(i);
                    stats.add(new ColumnStat(col, col + "_enc", rs.getLong(4 + i * 3), rs.getLong(5 + i * 3), rs.getLong(6 + i * 3)));
                }
                return new TableView(plan.connectorId(), plan.database(), plan.schema(), plan.table(), rows, withPi,
                    withPi - pending, pending, stats, null);
            }
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "query failed" : e.getMessage().lines().findFirst().orElse("");
            if (Objects.equals(e.getSQLState(), "42703")) {
                message = "Encrypted columns are missing; run the Implementation stage again";
            }
            return new TableView(plan.connectorId(), plan.database(), plan.schema(), plan.table(), 0, 0, 0, 0, List.of(), message);
        }
    }

    /**
     * One page of a table's rows for the viewer. Only tables of the approved implementation can be read,
     * plain values are masked and each value is checked by decrypting its ciphertext.
     *
     * @param filter ALL, ENCRYPTED (rows with PI, all encrypted) or PENDING (rows with a value not encrypted)
     */
    @Transactional(readOnly = true)
    public RowsPage rows(UUID workspaceId, UUID connectorId, String schema, String tableName, String filter,
                         int page, int size, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        ImplementationRun impl = approvedImplementation(workspaceId);
        if (impl == null) throw new ValidationException("No approved implementation");
        TablePlan plan = plan(workspaceId, impl).stream()
            .filter(t -> Objects.equals(t.connectorId(), connectorId) && t.schema().equals(schema) && t.table().equals(tableName))
            .findFirst()
            .orElseThrow(() -> new ValidationException("Only tables of the approved implementation can be viewed"));
        DatabaseConnector connector = dbConnectorRepository.findById(connectorId)
            .orElseThrow(() -> new ValidationException("The database connector no longer exists"));
        String mode = filter == null ? "ALL" : filter.toUpperCase();
        String where = switch (mode) {
            case "PENDING" -> " WHERE " + anyPending(plan);
            case "ENCRYPTED" -> " WHERE (" + anyValue(plan) + ") AND NOT (" + anyPending(plan) + ")";
            case "ALL" -> "";
            default -> throw new ValidationException("Unknown filter " + filter);
        };
        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        int pageNo = Math.max(0, page);
        String table = quote(plan.schema()) + "." + quote(plan.table());
        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < plan.columns().size(); i++) {
            String col = plan.columns().get(i);
            select.append(i == 0 ? "" : ", ").append(quote(col)).append("::text, ").append(quote(col + "_enc"));
        }
        select.append(" FROM ").append(table).append(where).append(" ORDER BY ctid LIMIT ").append(pageSize)
            .append(" OFFSET ").append((long) pageNo * pageSize);

        try (Connection conn = dbConnectorService.openConnection(connector); Statement st = conn.createStatement()) {
            st.setQueryTimeout(60);
            long total;
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table + where)) {
                rs.next();
                total = rs.getLong(1);
            }
            List<List<CellView>> rows = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(select.toString())) {
                while (rs.next()) {
                    List<CellView> row = new ArrayList<>();
                    for (int i = 0; i < plan.columns().size(); i++) {
                        String plain = rs.getString(1 + i * 2);
                        String enc = rs.getString(2 + i * 2);
                        row.add(new CellView(plan.columns().get(i), mask(plain), preview(enc), cellState(plain, enc)));
                    }
                    rows.add(row);
                }
            }
            return new RowsPage(plan.schema(), plan.table(), mode, total, pageNo, pageSize, plan.columns(), rows);
        } catch (SQLException e) {
            throw new ValidationException("Could not read " + plan.schema() + "." + plan.table() + ": "
                + (e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse("")));
        }
    }

    /** EMPTY, PENDING, VERIFIED (decrypts to the plain value) or MISMATCH. */
    private String cellState(String plain, String enc) {
        if (plain == null && enc == null) return "EMPTY";
        if (enc == null) return "PENDING";
        try {
            return plain != null && cipher.isEncrypted(enc) && plain.equals(cipher.decrypt(enc)) ? "VERIFIED" : "MISMATCH";
        } catch (RuntimeException e) {
            return "MISMATCH";
        }
    }

    private static String anyValue(TablePlan plan) {
        return plan.columns().stream().map(c -> quote(c) + " IS NOT NULL").collect(Collectors.joining(" OR "));
    }

    private static String anyPending(TablePlan plan) {
        return plan.columns().stream().map(c -> "(" + quote(c) + " IS NOT NULL AND " + quote(c + "_enc") + " IS NULL)")
            .collect(Collectors.joining(" OR "));
    }

    /** Keeps just enough of a value to recognise it: "asha@example.com" -> "as**@example.com", "Rao" -> "R**". */
    static String mask(String value) {
        if (value == null) return null;
        int at = value.indexOf('@');
        if (at > 0) {
            String local = value.substring(0, at);
            return local.substring(0, Math.min(2, local.length())) + "**" + value.substring(at);
        }
        if (value.length() <= 4) return value.charAt(0) + "**";
        return value.substring(0, 2) + "*".repeat(Math.min(6, value.length() - 4)) + value.substring(value.length() - 2);
    }

    static String preview(String ciphertext) {
        if (ciphertext == null) return null;
        return ciphertext.length() <= 28 ? ciphertext : ciphertext.substring(0, 28) + "…";
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String label(DatabaseConnector c) {
        return c.getHost() + ":" + c.getPort() + "/" + c.getDatabaseName();
    }

    private RunView toView(DataSyncRun r) {
        List<Map<String, Object>> tables = List.of();
        if (r.getTablesJson() != null) {
            try {
                tables = objectMapper.readValue(r.getTablesJson(), new TypeReference<>() {});
            } catch (Exception e) {
                log.debug("Unreadable tables JSON for data sync {}", r.getId());
            }
        }
        return new RunView(r.getId(), r.getStatus(), r.getPhase(), r.getRowsEncrypted(), r.getValuesEncrypted(),
            r.getValuesVerified(), r.getVerifyFailures(), r.getRowsVerified(), r.getRowsFailed(), tables,
            r.getLog() == null ? List.of() : r.getLog().lines().toList(),
            r.getCreatedAt(), r.getStartedAt(), r.getCompletedAt(), r.getErrorMessage());
    }
}
