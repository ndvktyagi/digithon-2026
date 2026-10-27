package com.picopilot.scanner.service;

import com.picopilot.common.event.StageWorkCompletedEvent;
import com.picopilot.common.model.ScanStatus;
import com.picopilot.common.model.WorkspaceStage;
import com.picopilot.inventory.model.PiFinding;
import com.picopilot.inventory.repository.PiFindingRepository;
import com.picopilot.project.database.DatabaseConnectorService;
import com.picopilot.project.model.DatabaseConnector;
import com.picopilot.project.repository.DatabaseConnectorRepository;
import com.picopilot.scanner.model.ScanJob;
import com.picopilot.scanner.model.ScanScope;
import com.picopilot.scanner.model.ScanTarget;
import com.picopilot.scanner.repository.ScanJobRepository;
import com.picopilot.scanner.repository.ScanScopeRepository;
import com.picopilot.scanner.repository.ScanTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Runs a scan job: connects to each database target in execution order, reads table
 * metadata, applies {@link PiDetectionRules} and records findings. Lives in its own bean
 * so {@link Async} goes through the Spring proxy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScanExecutor {

    private static final int SAMPLE_ROWS = 50;
    private static final String ANALYSIS_VERSION = "rules-1.0";

    private static final String COLUMNS_SQL = """
        SELECT c.table_schema, c.table_name, c.column_name, c.data_type, c.is_nullable
        FROM information_schema.columns c
        JOIN information_schema.tables t
          ON t.table_schema = c.table_schema AND t.table_name = c.table_name
        WHERE t.table_type = 'BASE TABLE'
          AND c.table_schema NOT IN ('information_schema', 'pg_catalog')
          AND c.table_schema NOT LIKE 'pg\\_%'
          AND (CAST(? AS text[]) IS NULL OR c.table_schema = ANY(CAST(? AS text[])))
        ORDER BY c.table_schema, c.table_name, c.ordinal_position
        """;

    private static final String KEYS_SQL = """
        SELECT n.nspname, cl.relname, a.attname, con.contype,
               CASE WHEN con.contype = 'f' THEN fn.nspname || '.' || fcl.relname END
        FROM pg_constraint con
        JOIN pg_class cl ON cl.oid = con.conrelid
        JOIN pg_namespace n ON n.oid = cl.relnamespace
        JOIN LATERAL unnest(con.conkey) AS k(attnum) ON TRUE
        JOIN pg_attribute a ON a.attrelid = cl.oid AND a.attnum = k.attnum
        LEFT JOIN pg_class fcl ON fcl.oid = con.confrelid
        LEFT JOIN pg_namespace fn ON fn.oid = fcl.relnamespace
        WHERE con.contype IN ('p', 'f')
        """;

    private record ColumnInfo(String schema, String table, String column, String dataType, boolean nullable) {}

    private record KeyInfo(boolean primaryKey, String foreignKeyReference) {}

    private final ScanJobRepository scanJobRepository;
    private final ScanTargetRepository targetRepository;
    private final ScanScopeRepository scopeRepository;
    private final PiFindingRepository findingRepository;
    private final DatabaseConnectorRepository connectorRepository;
    private final DatabaseConnectorService connectorService;
    private final ApplicationEventPublisher events;

    @Async
    public void execute(UUID scanJobId) {
        ScanJob job = scanJobRepository.findById(scanJobId).orElse(null);
        if (job == null) {
            log.warn("Scan job {} vanished before execution", scanJobId);
            return;
        }
        boolean sampleValues = scopeRepository.findByScanJobId(scanJobId)
            .map(ScanScope::isValuePatternScanEnabled).orElse(false);
        List<ScanTarget> targets = targetRepository.findByScanJobIdOrderByExecutionOrderAsc(scanJobId);
        log.info("Scan {} started with {} database target(s)", scanJobId, targets.size());

        int failed = 0;
        for (ScanTarget target : targets) {
            if (isCancelled(scanJobId)) {
                markSkipped(target);
                continue;
            }
            try {
                scanTarget(job, target, sampleValues);
            } catch (CancellationException e) {
                ScanTarget current = reload(target);
                current.setStatus(ScanStatus.CANCELLED);
                current.setCompletedAt(Instant.now());
                targetRepository.save(current);
            } catch (Exception e) {
                failed++;
                log.warn("Scan {} target {} failed: {}", scanJobId, target.getLabel(), e.getMessage());
                ScanTarget current = reload(target);
                current.setStatus(ScanStatus.FAILED);
                current.setErrorMessage(truncate(describe(e)));
                current.setCompletedAt(Instant.now());
                targetRepository.save(current);
            }
            updateTotals(scanJobId);
        }
        finish(scanJobId, targets.size(), failed);
    }

    /** Scans left RUNNING by a previous server process can never finish; mark them failed. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedScans() {
        List<ScanJob> stale = scanJobRepository.findByStatusIn(List.of(ScanStatus.RUNNING));
        for (ScanJob job : stale) {
            job.setStatus(ScanStatus.FAILED);
            job.setErrorMessage("Interrupted by a server restart. Start a new scan.");
            job.setCompletedAt(Instant.now());
            scanJobRepository.save(job);
            for (ScanTarget t : targetRepository.findByScanJobIdOrderByExecutionOrderAsc(job.getId())) {
                if (t.getStatus() == ScanStatus.RUNNING || t.getStatus() == ScanStatus.PENDING) {
                    t.setStatus(ScanStatus.FAILED);
                    t.setErrorMessage("Interrupted by a server restart");
                    targetRepository.save(t);
                }
            }
        }
        if (!stale.isEmpty()) {
            log.info("Marked {} interrupted scan(s) as failed", stale.size());
        }
    }

    private void scanTarget(ScanJob job, ScanTarget target, boolean sampleValues) throws SQLException {
        // save() returns the entity with its new @Version; keep using the returned instance
        target.setStatus(ScanStatus.RUNNING);
        target.setStartedAt(Instant.now());
        target = targetRepository.save(target);

        DatabaseConnector connector = Optional.ofNullable(target.getConnectorId())
            .flatMap(connectorRepository::findById)
            .orElseThrow(() -> new IllegalStateException("Connector no longer exists"));

        try (Connection connection = connectorService.openConnection(connector)) {
            Map<String, List<ColumnInfo>> tables = readColumns(connection, target.getSchemas());
            Map<String, KeyInfo> keys = readKeys(connection);

            for (List<ColumnInfo> columns : tables.values()) {
                if (isCancelled(job.getId())) {
                    throw new CancellationException();
                }
                Set<String> names = new HashSet<>();
                columns.forEach(c -> names.add(c.column().toLowerCase(java.util.Locale.ROOT)));
                List<PiFinding> findings = new ArrayList<>();
                for (ColumnInfo column : columns) {
                    String plainBase = PiDetectionRules.encryptedTwinBase(column.column(), names);
                    if (plainBase != null) {
                        // Encrypted twin of a plain column: already protected, never offered for encryption
                        PiDetectionRules.matchColumn(column.table(), plainBase).ifPresent(match -> {
                            PiFinding f = toFinding(job, connector, column, keys, match);
                            markEncrypted(f, "Encrypted copy of \"" + plainBase + "\"; it holds ciphertext, not plain PI.");
                            findings.add(f);
                        });
                        continue;
                    }
                    // Encrypted column whose plain column was dropped after a release: checked by its values
                    // even when value sampling is off, so it is never offered for encryption again
                    String releasedBase = PiDetectionRules.encryptedSuffixBase(column.column());
                    if (releasedBase != null && PiDetectionRules.isTextType(column.dataType())
                        && PiDetectionRules.isReleasedEncryptedColumn(sampleOrEmpty(connection, column))) {
                        PiDetectionRules.matchColumn(column.table(), releasedBase).ifPresent(match -> {
                            PiFinding f = toFinding(job, connector, column, keys, match);
                            markEncrypted(f, "Encrypted column (holds " + PiDetectionRules.CIPHERTEXT_PREFIX
                                + " ciphertext); its plain column \"" + releasedBase + "\" was removed after release.");
                            findings.add(f);
                        });
                        continue;
                    }
                    Optional<PiDetectionRules.Match> match = detect(connection, column, sampleValues);
                    if (match.isEmpty()) continue;
                    PiFinding f = toFinding(job, connector, column, keys, match.get());
                    if (sampleValues && PiDetectionRules.isTextType(column.dataType())
                        && PiDetectionRules.looksEncrypted(sampleOrEmpty(connection, column))) {
                        markEncrypted(f, "Sampled values are already encrypted (" + PiDetectionRules.CIPHERTEXT_PREFIX + ").");
                    } else if (names.contains(column.column().toLowerCase(java.util.Locale.ROOT) + PiDetectionRules.ENCRYPTED_SUFFIX)) {
                        f.setEncryptedColumn(column.column() + PiDetectionRules.ENCRYPTED_SUFFIX);
                    }
                    findings.add(f);
                }
                findingRepository.saveAll(findings);
                long plainPi = findings.stream().filter(f -> !PiFinding.ENCRYPTED.equals(f.getEncryptionState())).count();

                target.setTablesScanned(target.getTablesScanned() + 1);
                target.setColumnsScanned(target.getColumnsScanned() + columns.size());
                target.setFindingsCount(target.getFindingsCount() + (int) plainPi);
                target = targetRepository.save(target);
            }
        }
        target.setStatus(ScanStatus.COMPLETED);
        target.setCompletedAt(Instant.now());
        targetRepository.save(target);
    }

    private Optional<PiDetectionRules.Match> detect(Connection connection, ColumnInfo column, boolean sampleValues) {
        Optional<PiDetectionRules.Match> byName = PiDetectionRules.matchColumn(column.table(), column.column());
        if (byName.isPresent() || !sampleValues || !PiDetectionRules.isTextType(column.dataType())) {
            return byName;
        }
        try {
            return PiDetectionRules.matchValues(sample(connection, column));
        } catch (SQLException e) {
            // A table we cannot read (permissions) should not fail the whole database
            log.debug("Could not sample {}.{}.{}: {}", column.schema(), column.table(), column.column(), e.getMessage());
            return Optional.empty();
        }
    }

    private static void markEncrypted(PiFinding f, String reasoning) {
        f.setEncryptionState(PiFinding.ENCRYPTED);
        f.setSuggestedAction("NONE");
        f.setReasoning(reasoning);
    }

    private List<String> sampleOrEmpty(Connection connection, ColumnInfo column) {
        try {
            return sample(connection, column);
        } catch (SQLException e) {
            return List.of();
        }
    }

    private List<String> sample(Connection connection, ColumnInfo column) throws SQLException {
        String col = quote(column.column());
        String sql = "SELECT " + col + "::text FROM " + quote(column.schema()) + "." + quote(column.table())
            + " WHERE " + col + " IS NOT NULL LIMIT " + SAMPLE_ROWS;
        List<String> values = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(15);
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
            }
        }
        return values;
    }

    private Map<String, List<ColumnInfo>> readColumns(Connection connection, String schemaScope) throws SQLException {
        String[] schemas = schemaScope == null || schemaScope.isBlank() ? null
            : Arrays.stream(schemaScope.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        Map<String, List<ColumnInfo>> tables = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(COLUMNS_SQL)) {
            Array schemaArray = schemas == null ? null : connection.createArrayOf("text", schemas);
            ps.setArray(1, schemaArray);
            ps.setArray(2, schemaArray);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo c = new ColumnInfo(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), "YES".equals(rs.getString(5)));
                    tables.computeIfAbsent(c.schema() + "." + c.table(), k -> new ArrayList<>()).add(c);
                }
            }
        }
        return tables;
    }

    private Map<String, KeyInfo> readKeys(Connection connection) throws SQLException {
        Map<String, KeyInfo> keys = new HashMap<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(KEYS_SQL)) {
            while (rs.next()) {
                String key = rs.getString(1) + "." + rs.getString(2) + "." + rs.getString(3);
                boolean pk = "p".equals(rs.getString(4));
                KeyInfo existing = keys.get(key);
                keys.put(key, new KeyInfo(pk || (existing != null && existing.primaryKey()),
                    pk ? (existing == null ? null : existing.foreignKeyReference()) : rs.getString(5)));
            }
        }
        return keys;
    }

    private PiFinding toFinding(ScanJob job, DatabaseConnector connector, ColumnInfo column,
                                Map<String, KeyInfo> keys, PiDetectionRules.Match match) {
        KeyInfo key = keys.get(column.schema() + "." + column.table() + "." + column.column());
        PiFinding f = new PiFinding();
        f.setWorkspaceId(job.getWorkspaceId());
        f.setProjectId(connector.getProject().getId());
        f.setScanJobId(job.getId());
        f.setDatabaseConnectorId(connector.getId());
        f.setDbSchema(column.schema());
        f.setTableName(column.table());
        f.setColumnName(column.column());
        f.setDataType(column.dataType());
        f.setNullable(column.nullable());
        f.setPrimaryKey(key != null && key.primaryKey());
        f.setForeignKey(key != null && key.foreignKeyReference() != null);
        f.setForeignKeyReference(key == null ? null : key.foreignKeyReference());
        f.setCandidateCategory(match.category());
        f.setConfidence(match.confidence());
        f.setDetectionMethod(match.rule().startsWith("VALUE_") ? "VALUE_PATTERN" : "RULE");
        f.setDetectorRule(match.rule());
        f.setReasoning(match.reasoning());
        f.setSuggestedAction("ENCRYPT");
        f.setAnalysisEngine("RULES_ONLY");
        f.setAnalysisVersion(ANALYSIS_VERSION);
        f.setFieldOrderHash(hash(job.getId() + "|" + connector.getId() + "|" + column.schema()
            + "|" + column.table() + "|" + column.column()));
        return f;
    }

    private void updateTotals(UUID scanJobId) {
        try {
            saveTotals(scanJobId);
        } catch (ObjectOptimisticLockingFailureException e) {
            // The user stopped the scan concurrently; the next update or finish() catches up
            log.debug("Skipped totals update for scan {}: concurrent change", scanJobId);
        }
    }

    private void saveTotals(UUID scanJobId) {
        scanJobRepository.findById(scanJobId).ifPresent(job -> {
            List<ScanTarget> targets = targetRepository.findByScanJobIdOrderByExecutionOrderAsc(scanJobId);
            job.setTotalTablesScanned(targets.stream().mapToInt(ScanTarget::getTablesScanned).sum());
            job.setFindingsCount(targets.stream().mapToInt(ScanTarget::getFindingsCount).sum());
            Set<String> piTables = new HashSet<>();
            for (PiFinding f : findingRepository.findByScanJobId(scanJobId)) {
                if (PiFinding.ENCRYPTED.equals(f.getEncryptionState())) continue;
                piTables.add(f.getDatabaseConnectorId() + "." + f.getDbSchema() + "." + f.getTableName());
            }
            job.setTablesWithPi(piTables.size());
            scanJobRepository.save(job);
        });
    }

    private void finish(UUID scanJobId, int targetCount, int failed) {
        try {
            complete(scanJobId, targetCount, failed);
        } catch (ObjectOptimisticLockingFailureException e) {
            complete(scanJobId, targetCount, failed);
        }
    }

    private void complete(UUID scanJobId, int targetCount, int failed) {
        scanJobRepository.findById(scanJobId).ifPresent(job -> {
            if (job.getStatus() == ScanStatus.CANCELLED) {
                log.info("Scan {} stopped by user", scanJobId);
                return;
            }
            job.setCompletedAt(Instant.now());
            if (targetCount > 0 && failed == targetCount) {
                job.setStatus(ScanStatus.FAILED);
                job.setErrorMessage("All databases failed to scan. See each database for details.");
            } else {
                job.setStatus(ScanStatus.COMPLETED);
                job.setErrorMessage(failed > 0 ? failed + " of " + targetCount + " databases failed to scan." : null);
            }
            scanJobRepository.save(job);
            log.info("Scan {} finished: {} ({} findings)", scanJobId, job.getStatus(), job.getFindingsCount());
            if (job.getStatus() == ScanStatus.COMPLETED) {
                String actor = job.getCreatedBy() != null ? job.getCreatedBy() : "system";
                events.publishEvent(new StageWorkCompletedEvent(job.getWorkspaceId(), WorkspaceStage.SCAN, actor));
            }
        });
    }

    private ScanTarget reload(ScanTarget target) {
        return targetRepository.findById(target.getId()).orElse(target);
    }

    private boolean isCancelled(UUID scanJobId) {
        return scanJobRepository.findById(scanJobId)
            .map(j -> j.getStatus() == ScanStatus.CANCELLED).orElse(true);
    }

    private void markSkipped(ScanTarget target) {
        if (target.getStatus() == ScanStatus.PENDING) {
            target.setStatus(ScanStatus.CANCELLED);
            targetRepository.save(target);
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String describe(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static String truncate(String s) {
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class CancellationException extends RuntimeException {}
}
