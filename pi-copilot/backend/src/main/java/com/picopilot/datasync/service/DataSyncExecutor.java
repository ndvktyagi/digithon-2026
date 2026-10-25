package com.picopilot.datasync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picopilot.common.event.StageWorkCompletedEvent;
import com.picopilot.common.model.WorkspaceStage;
import com.picopilot.datasync.model.DataSyncRun;
import com.picopilot.datasync.repository.DataSyncRunRepository;
import com.picopilot.datasync.service.DataSyncService.TablePlan;
import com.picopilot.encryption.local.LocalFieldCipher;
import com.picopilot.project.database.DatabaseConnectorService;
import com.picopilot.project.model.DatabaseConnector;
import com.picopilot.project.repository.DatabaseConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.picopilot.datasync.service.DataSyncService.quote;

/**
 * Fills each {@code <column>_enc} with the encrypted plain value (LOCAL_DEV AES-256-GCM, the same
 * "enc:v1:" format the application uses), in batches, then verifies every encrypted value decrypts
 * back to its plain value. Plain columns are never modified.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncExecutor {

    private static final int BATCH_SIZE = 500;

    private final DataSyncRunRepository runRepository;
    private final DatabaseConnectorRepository dbConnectorRepository;
    private final DatabaseConnectorService dbConnectorService;
    private final LocalFieldCipher cipher;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    /** Per-table counters reported in tables_json. */
    private static final class TableResult {
        final TablePlan plan;
        long rowsEncrypted;
        /** Rows whose encrypted copy no longer matched the plain value and was re-encrypted. */
        long rowsRefreshed;
        long rowsVerified;
        long rowsFailed;
        final Map<String, long[]> columns = new LinkedHashMap<>(); // column -> [encrypted, verified, failures]

        TableResult(TablePlan plan) {
            this.plan = plan;
            plan.columns().forEach(c -> columns.put(c, new long[3]));
        }

        Map<String, Object> toJson() {
            List<Map<String, Object>> cols = new ArrayList<>();
            columns.forEach((c, n) -> cols.add(Map.of("column", c, "encryptedColumn", c + "_enc",
                "encrypted", n[0], "verified", n[1], "failures", n[2])));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("database", plan.database());
            m.put("schema", plan.schema());
            m.put("table", plan.table());
            m.put("rowsEncrypted", rowsEncrypted);
            m.put("rowsRefreshed", rowsRefreshed);
            m.put("rowsVerified", rowsVerified);
            m.put("rowsFailed", rowsFailed);
            m.put("columns", cols);
            return m;
        }
    }

    @Async
    public void execute(UUID runId, List<TablePlan> plans) {
        DataSyncRun run = runRepository.findById(runId).orElse(null);
        if (run == null) return;
        StringBuilder logText = new StringBuilder();
        List<TableResult> results = plans.stream().map(TableResult::new).toList();
        try {
            run.setStatus(DataSyncRun.RUNNING);
            run.setPhase("ENCRYPTING");
            run.setStartedAt(Instant.now());
            run = save(run, results, logText, "Data sync started for " + plans.size() + " table(s)");

            for (TableResult t : results) {
                run = encryptTable(run, t, results, logText);
                run = refreshTable(run, t, results, logText);
            }
            run.setPhase("VERIFYING");
            run = save(run, results, logText, "Encryption done; verifying every encrypted value");
            for (TableResult t : results) {
                run = verifyTable(run, t, results, logText);
            }

            run.setPhase(null);
            run.setCompletedAt(Instant.now());
            if (run.getVerifyFailures() > 0) {
                run.setStatus(DataSyncRun.FAILED);
                run.setErrorMessage(run.getVerifyFailures() + " value(s) did not verify; see the table results");
                save(run, results, logText, "Verification failed for " + run.getVerifyFailures() + " value(s)");
            } else {
                run.setStatus(DataSyncRun.COMPLETED);
                save(run, results, logText, "Data sync completed: " + run.getRowsVerified() + " row(s) encrypted and verified");
                events.publishEvent(new StageWorkCompletedEvent(run.getWorkspaceId(), WorkspaceStage.DATA_SYNC,
                    run.getCreatedBy() != null ? run.getCreatedBy() : "system"));
            }
        } catch (Exception e) {
            log.warn("Data sync {} failed: {}", runId, e.getMessage());
            run.setStatus(DataSyncRun.FAILED);
            run.setPhase(null);
            run.setCompletedAt(Instant.now());
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().lines().findFirst().orElse("");
            run.setErrorMessage(message.length() > 2000 ? message.substring(0, 2000) : message);
            save(run, results, logText, "Failed: " + message);
        }
    }

    /** A restarted server abandons running syncs; already encrypted rows are kept, so a new run resumes. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedRuns() {
        for (DataSyncRun run : runRepository.findByStatusIn(List.of(DataSyncRun.PENDING, DataSyncRun.RUNNING))) {
            run.setStatus(DataSyncRun.FAILED);
            run.setPhase(null);
            run.setErrorMessage("Interrupted by a server restart. Start Data Sync again to continue where it stopped.");
            run.setCompletedAt(Instant.now());
            runRepository.save(run);
        }
    }

    private DataSyncRun encryptTable(DataSyncRun run, TableResult t, List<TableResult> all, StringBuilder logText)
        throws SQLException {
        TablePlan plan = t.plan;
        String table = quote(plan.schema()) + "." + quote(plan.table());
        StringBuilder select = new StringBuilder("SELECT ctid::text");
        StringBuilder where = new StringBuilder();
        StringBuilder update = new StringBuilder("UPDATE ").append(table).append(" SET ");
        for (int i = 0; i < plan.columns().size(); i++) {
            String p = quote(plan.columns().get(i)), e = quote(plan.columns().get(i) + "_enc");
            select.append(", ").append(p).append("::text, ").append(e);
            where.append(i == 0 ? "" : " OR ").append('(').append(p).append(" IS NOT NULL AND ").append(e).append(" IS NULL)");
            update.append(i == 0 ? "" : ", ").append(e).append(" = ?");
        }
        select.append(" FROM ").append(table).append(" WHERE ").append(where).append(" LIMIT ").append(BATCH_SIZE)
            .append(" FOR UPDATE");
        update.append(" WHERE ctid = ?::tid");
        appendLog(logText, "Encrypting " + plan.schema() + "." + plan.table() + " (" + String.join(", ", plan.columns()) + ")");

        try (Connection conn = dbConnectorService.openWriteConnection(connector(plan))) {
            while (true) {
                int batchRows = 0;
                long batchValues = 0;
                try (Statement st = conn.createStatement(); PreparedStatement up = conn.prepareStatement(update.toString())) {
                    st.setQueryTimeout(120);
                    try (ResultSet rs = st.executeQuery(select.toString())) {
                        while (rs.next()) {
                            for (int i = 0; i < plan.columns().size(); i++) {
                                String plain = rs.getString(2 + i * 2);
                                String existing = rs.getString(3 + i * 2);
                                String value = existing != null || plain == null ? existing : cipher.encrypt(plain);
                                if (existing == null && plain != null) {
                                    batchValues++;
                                    t.columns.get(plan.columns().get(i))[0]++;
                                }
                                up.setString(1 + i, value);
                            }
                            up.setString(plan.columns().size() + 1, rs.getString(1));
                            up.addBatch();
                            batchRows++;
                        }
                    }
                    if (batchRows > 0) up.executeBatch();
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw new SQLException("Could not encrypt " + plan.schema() + "." + plan.table() + ": "
                        + e.getMessage().lines().findFirst().orElse(""), e);
                }
                if (batchRows == 0) break;
                t.rowsEncrypted += batchRows;
                run.setRowsEncrypted(run.getRowsEncrypted() + batchRows);
                run.setValuesEncrypted(run.getValuesEncrypted() + batchValues);
                run = save(run, all, logText, null);
                if (batchRows < BATCH_SIZE) break;
            }
        }
        appendLog(logText, plan.schema() + "." + plan.table() + ": " + t.rowsEncrypted + " row(s) encrypted");
        return run;
    }

    /**
     * While encrypted read is off the application writes only the plain columns, so rows changed since the last
     * sync have a stale encrypted copy. Re-encrypts every copy that no longer decrypts to the plain value
     * (and clears copies whose plain value was removed), so switching encrypted read on never serves old data.
     */
    private DataSyncRun refreshTable(DataSyncRun run, TableResult t, List<TableResult> all, StringBuilder logText)
        throws SQLException {
        TablePlan plan = t.plan;
        String table = quote(plan.schema()) + "." + quote(plan.table());
        StringBuilder select = new StringBuilder("SELECT ctid::text");
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < plan.columns().size(); i++) {
            String p = quote(plan.columns().get(i)), e = quote(plan.columns().get(i) + "_enc");
            select.append(", ").append(p).append("::text, ").append(e);
            where.append(i == 0 ? "" : " OR ").append(e).append(" IS NOT NULL");
        }
        select.append(" FROM ").append(table).append(" WHERE ").append(where);
        List<String[]> stale = new ArrayList<>(); // ctid, then the new encrypted value per column
        try (Connection conn = dbConnectorService.openConnection(connector(plan))) {
            conn.setAutoCommit(false); // lets the driver stream large tables with a cursor
            try (Statement st = conn.createStatement()) {
                st.setFetchSize(1000);
                try (ResultSet rs = st.executeQuery(select.toString())) {
                    while (rs.next()) {
                        String[] row = new String[plan.columns().size() + 1];
                        row[0] = rs.getString(1);
                        boolean changed = false;
                        for (int i = 0; i < plan.columns().size(); i++) {
                            String plain = rs.getString(2 + i * 2);
                            String enc = rs.getString(3 + i * 2);
                            boolean current = enc == null ? plain == null
                                : plain != null && cipher.isEncrypted(enc) && Objects.equals(plain, decrypt(enc));
                            if (enc == null && plain != null) current = false;
                            row[1 + i] = current ? enc : plain == null ? null : cipher.encrypt(plain);
                            changed |= !current;
                        }
                        if (changed) stale.add(row);
                    }
                }
            }
        }
        if (stale.isEmpty()) return run;
        StringBuilder update = new StringBuilder("UPDATE ").append(table).append(" SET ");
        for (int i = 0; i < plan.columns().size(); i++) {
            update.append(i == 0 ? "" : ", ").append(quote(plan.columns().get(i) + "_enc")).append(" = ?");
        }
        update.append(" WHERE ctid = ?::tid");
        try (Connection conn = dbConnectorService.openWriteConnection(connector(plan));
             PreparedStatement up = conn.prepareStatement(update.toString())) {
            try {
                for (int r = 0; r < stale.size(); r++) {
                    String[] row = stale.get(r);
                    for (int i = 0; i < plan.columns().size(); i++) up.setString(1 + i, row[1 + i]);
                    up.setString(plan.columns().size() + 1, row[0]);
                    up.addBatch();
                    if ((r + 1) % BATCH_SIZE == 0) up.executeBatch();
                }
                up.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new SQLException("Could not refresh " + plan.schema() + "." + plan.table() + ": "
                    + e.getMessage().lines().findFirst().orElse(""), e);
            }
        }
        t.rowsRefreshed = stale.size();
        return save(run, all, logText, plan.schema() + "." + plan.table() + ": " + stale.size()
            + " row(s) changed since the last sync; encrypted copies refreshed");
    }

    private DataSyncRun verifyTable(DataSyncRun run, TableResult t, List<TableResult> all, StringBuilder logText)
        throws SQLException {
        TablePlan plan = t.plan;
        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < plan.columns().size(); i++) {
            select.append(i == 0 ? "" : ", ").append(quote(plan.columns().get(i))).append("::text, ")
                .append(quote(plan.columns().get(i) + "_enc"));
        }
        select.append(" FROM ").append(quote(plan.schema())).append('.').append(quote(plan.table()));
        long verified = 0, failures = 0;
        try (Connection conn = dbConnectorService.openConnection(connector(plan))) {
            conn.setAutoCommit(false); // lets the driver stream large tables with a cursor
            try (Statement st = conn.createStatement()) {
                st.setFetchSize(1000);
                try (ResultSet rs = st.executeQuery(select.toString())) {
                    while (rs.next()) {
                        boolean rowHasPi = false;
                        boolean rowOk = true;
                        for (int i = 0; i < plan.columns().size(); i++) {
                            String plain = rs.getString(1 + i * 2);
                            String enc = rs.getString(2 + i * 2);
                            if (plain == null && enc == null) continue;
                            rowHasPi = true;
                            long[] counters = t.columns.get(plan.columns().get(i));
                            if (plain != null && enc != null && cipher.isEncrypted(enc) && Objects.equals(plain, decrypt(enc))) {
                                counters[1]++;
                                verified++;
                            } else {
                                counters[2]++;
                                failures++;
                                rowOk = false;
                            }
                        }
                        if (rowHasPi) {
                            if (rowOk) t.rowsVerified++; else t.rowsFailed++;
                        }
                    }
                }
            }
        }
        run.setValuesVerified(run.getValuesVerified() + verified);
        run.setVerifyFailures(run.getVerifyFailures() + failures);
        run.setRowsVerified(run.getRowsVerified() + t.rowsVerified);
        run.setRowsFailed(run.getRowsFailed() + t.rowsFailed);
        return save(run, all, logText, plan.schema() + "." + plan.table() + ": " + t.rowsVerified + " row(s) verified"
            + (t.rowsFailed > 0 ? ", " + t.rowsFailed + " row(s) FAILED" : ""));
    }

    private String decrypt(String enc) {
        try {
            return cipher.decrypt(enc);
        } catch (RuntimeException e) {
            return null; // counted as a verification failure
        }
    }

    private DatabaseConnector connector(TablePlan plan) {
        return plan.connectorId() == null ? missing(plan)
            : dbConnectorRepository.findById(plan.connectorId()).orElseGet(() -> missing(plan));
    }

    private static DatabaseConnector missing(TablePlan plan) {
        throw new IllegalStateException("The database connector for " + plan.database() + " no longer exists");
    }

    private DataSyncRun save(DataSyncRun run, List<TableResult> results, StringBuilder logText, String line) {
        if (line != null) appendLog(logText, line);
        run.setLog(logText.toString());
        try {
            run.setTablesJson(objectMapper.writeValueAsString(results.stream().map(TableResult::toJson).toList()));
        } catch (Exception e) {
            log.debug("Could not serialize data sync results", e);
        }
        // save() returns the entity with its new @Version; always continue with the returned instance
        return runRepository.save(run);
    }

    private static void appendLog(StringBuilder logText, String line) {
        logText.append(Instant.now()).append(' ').append(line).append('\n');
    }
}
