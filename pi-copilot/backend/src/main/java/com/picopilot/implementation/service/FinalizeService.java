package com.picopilot.implementation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.picopilot.audit.service.AuditService;
import com.picopilot.common.exception.InvalidStateException;
import com.picopilot.common.exception.ValidationException;
import com.picopilot.common.model.Severity;
import com.picopilot.common.model.UserRole;
import com.picopilot.common.model.WorkspaceStage;
import com.picopilot.encryption.local.LocalFieldCipher;
import com.picopilot.identity.repository.WorkspaceRepository;
import com.picopilot.identity.service.WorkspaceLifecycleService;
import com.picopilot.identity.service.WorkspaceService;
import com.picopilot.implementation.model.FinalizeRun;
import com.picopilot.implementation.model.ImplementationRun;
import com.picopilot.implementation.repository.FinalizeRunRepository;
import com.picopilot.implementation.repository.ImplementationRunRepository;
import com.picopilot.project.database.DatabaseConnectorService;
import com.picopilot.project.github.GitHubApiClient;
import com.picopilot.project.model.DatabaseConnector;
import com.picopilot.project.repository.DatabaseConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finalize, after the approved release branch was deployed. The user reports how the release went:
 * <ul>
 *   <li><b>Release succeeded</b>: every encrypted copy is brought up to date and the plain columns become nullable;
 *       a cleanup commit on the release branch moves each entity field onto its {@code _enc} column (through the
 *       converter), drops the generated copy and callbacks, makes native queries read only the encrypted columns
 *       and adds a Flyway migration dropping the plain columns; then the release pull request is merged into the
 *       base branch. Once the cleaned-up application is deployed, the plain columns are dropped and the workspace
 *       lifecycle completes.</li>
 *   <li><b>Release failed</b>: the release pull request is closed without merging, so the base branch stays the
 *       version to redeploy; PI Co-Pilot's Flyway history rows are removed so the base branch starts, and, if the
 *       user asks, the encrypted columns are dropped. The workspace lifecycle completes.</li>
 * </ul>
 * The steps are not transactional on PI Co-Pilot's side, so a failure is recorded on the run before it is reported.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinalizeService {

    private static final int MAX_FILE_FETCHES = 150;
    private static final int BATCH_SIZE = 500;
    private static final Pattern FLYWAY_FILE = Pattern.compile("(.*/db/migration/)V(\\d+)(?:_\\d+)*__[^/]+\\.sql$");
    private static final String DROP_MIGRATION = "__pi_copilot_drop_plain_columns.sql";
    /** Flyway scripts PI Co-Pilot adds; their history rows are removed after a failed release. */
    private static final String FLYWAY_SCRIPT_MARKER = "pi_copilot";
    /** Classes an earlier, flag-gated version generated; the cleanup deletes them where a branch still has them. */
    private static final List<String> RETIRED_CLASSES = List.of("PiEncryptionListener", "PiNativeQueryRouter",
        "PiEncryptionFlags", "PiEncryptedFields");

    private final FinalizeRunRepository runRepository;
    private final ImplementationRunRepository implementationRunRepository;
    private final DatabaseConnectorRepository dbConnectorRepository;
    private final DatabaseConnectorService dbConnectorService;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceService workspaceService;
    private final WorkspaceLifecycleService lifecycleService;
    private final PullRequestGateway pullRequests;
    private final GitHubApiClient github;
    private final LocalFieldCipher cipher;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** An approved column mapped to an entity field in Implementation. */
    record Column(UUID connectorId, String database, String schema, String table, String column, String field,
                  String entityFile) {}

    /**
     * @param releaseOutcome  SUCCEEDED or FAILED once the user reported it, else null
     * @param problems        what blocks the next step
     * @param warnings        repository queries that still run but cannot match encrypted values
     * @param changedFiles    "ADDED|path", "MODIFIED|path" or "DELETED|path"
     * @param columnsStatus   DROPPED (plain columns after success, encrypted ones after failure), KEPT or PENDING
     */
    public record Status(WorkspaceStage stage, UUID implementationRunId, String baseBranch, String releaseBranch,
                         Integer releasePrNumber, String releasePrUrl, List<String> columns, String releaseOutcome,
                         List<String> problems, boolean canChooseOutcome, boolean canCleanUp, boolean canDropColumns,
                         String codeStatus, String commitSha, String mergeStatus, String mergeMessage,
                         List<String> changedFiles, List<String> warnings, String columnsStatus,
                         List<String> droppedColumns, Instant droppedAt, String droppedBy, String errorMessage,
                         List<String> log) {}

    /** The cleanup commit before it is pushed; a null content deletes the file. */
    private record Cleanup(String headSha, String headTree, Map<String, String> changes, Map<String, String> kinds,
                           List<String> warnings) {}

    /** A server restart abandons a running cleanup; nothing was pushed unless a commit was recorded. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedRuns() {
        for (FinalizeRun run : runRepository.findByCodeStatus(FinalizeRun.RUNNING)) {
            run.setCodeStatus(FinalizeRun.FAILED);
            run.setErrorMessage("Interrupted by a server restart. Run the release cleanup again.");
            runRepository.save(run);
        }
    }

    public Status status(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return buildStatus(workspaceId);
    }

    /**
     * Release succeeded: syncs the encrypted copies, pushes the cleanup commit to the release branch and merges the
     * release pull request into the base branch. Safe to repeat: a pushed cleanup is not pushed again, and a failed
     * merge is only retried.
     *
     * @param acceptQueryWarnings the user accepts that the listed repository queries can no longer match values
     */
    public Status releaseSucceeded(UUID workspaceId, boolean acceptQueryWarnings, String userId) {
        workspaceService.requireRole(workspaceId, userId, UserRole.PLATFORM_ADMIN, UserRole.APP_OWNER);
        ImplementationRun impl = approvedImplementation(workspaceId);
        FinalizeRun latest = current(workspaceId, impl);
        if (latest != null && FinalizeRun.FAILED.equals(latest.getReleaseOutcome())) {
            throw new InvalidStateException("The release was reported as failed; its pull request is closed");
        }
        if (latest != null && FinalizeRun.COMPLETED.equals(latest.getCodeStatus())
            && !FinalizeRun.MERGED.equals(latest.getMergeStatus())) {
            // Pushed but not merged (e.g. branch protection or a conflict): only retry the merge
            StringBuilder logText = new StringBuilder(latest.getLog() == null ? "" : latest.getLog());
            merge(latest, impl, logText);
            return buildStatus(workspaceId);
        }
        Status status = buildStatus(workspaceId);
        if (!status.canCleanUp()) {
            throw new InvalidStateException(status.problems().isEmpty()
                ? "The release cleanup is already merged" : String.join("; ", status.problems()));
        }
        List<Column> columns = columns(impl, new ArrayList<>());

        FinalizeRun run = latest != null ? latest : new FinalizeRun();
        run.setWorkspaceId(workspaceId);
        run.setImplementationRunId(impl.getId());
        run.setReleaseOutcome(FinalizeRun.SUCCEEDED);
        run.setCodeStatus(FinalizeRun.RUNNING);
        run.setErrorMessage(null);
        StringBuilder logText = new StringBuilder(run.getLog() == null ? "" : run.getLog());
        run = save(run, logText, "Release succeeded: retiring the plain-text columns of " + columns.size() + " column(s)");
        try {
            PullRequestGateway.Repo repo = pullRequests.repo(impl.getRepositoryConnectorId());
            // Read and rewrite the code first: if anything blocks the cleanup, the databases stay untouched
            Cleanup cleanup = prepareCode(repo, impl, columns, logText);
            if (!cleanup.warnings().isEmpty() && !acceptQueryWarnings) {
                run.setCodeStatus(FinalizeRun.PENDING);
                run.setWarnings(String.join("\n", cleanup.warnings()));
                save(run, logText, "Waiting for confirmation: " + cleanup.warnings().size()
                    + " repository quer" + (cleanup.warnings().size() == 1 ? "y" : "ies") + " can no longer match values");
                throw new ValidationException("These repository queries compare or sort by values that will only exist "
                    + "encrypted, so they can no longer match: " + String.join("; ", cleanup.warnings())
                    + ". Change them, or confirm to continue anyway.");
            }
            updateDatabases(columns, false, logText);
            run = push(run, repo, impl, columns, cleanup, logText);
            run.setCodeStatus(FinalizeRun.COMPLETED);
            run = save(run, logText, "Cleanup pushed to " + impl.getBranchName());
            run = merge(run, impl, logText);
        } catch (ValidationException e) {
            throw e;
        } catch (SQLException | RuntimeException e) {
            log.warn("Release cleanup failed for workspace {}: {}", workspaceId, e.getMessage());
            run.setCodeStatus(FinalizeRun.FAILED);
            run.setErrorMessage(truncate(describe(e), 2000));
            save(run, logText, "Failed: " + describe(e));
            throw new InvalidStateException("Could not retire the plain-text code paths: " + describe(e));
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("columns", columns.size());
        metadata.put("mergeStatus", run.getMergeStatus());
        auditService.record(workspaceId, null, userId, null, "FINALIZE_RELEASE_SUCCEEDED", "IMPLEMENTATION",
            run.getId().toString(), impl.getBranchName(), "SUCCESS", Severity.INFO, null, null, metadata);
        return buildStatus(workspaceId);
    }

    /**
     * Release succeeded, last step: drops the plain columns once the merged, cleaned-up application is deployed,
     * then completes the lifecycle.
     *
     * @param deployed the user's confirmation that the merged cleanup is running
     */
    public Status dropColumns(UUID workspaceId, boolean deployed, String userId) {
        workspaceService.requireRole(workspaceId, userId, UserRole.PLATFORM_ADMIN, UserRole.APP_OWNER);
        if (!deployed) {
            throw new ValidationException("Confirm that the cleaned-up application is deployed before dropping the plain-text columns");
        }
        Status status = buildStatus(workspaceId);
        if (!status.canDropColumns()) {
            throw new InvalidStateException(!status.problems().isEmpty() ? String.join("; ", status.problems())
                : FinalizeRun.DROPPED.equals(status.columnsStatus()) ? "The plain-text columns are already dropped"
                : "Report the release as succeeded and merge the cleanup first");
        }
        ImplementationRun impl = approvedImplementation(workspaceId);
        List<Column> columns = columns(impl, new ArrayList<>());
        FinalizeRun run = current(workspaceId, impl);
        StringBuilder logText = new StringBuilder(run.getLog() == null ? "" : run.getLog());
        // The deployed base branch must really have the cleanup, or the application still maps the plain columns
        if (run.getCommitSha() != null && !pullRequests.containsCommit(pullRequests.repo(impl.getRepositoryConnectorId()),
                impl.getBaseBranch(), run.getCommitSha())) {
            run.setMergeStatus(FinalizeRun.FAILED);
            run.setMergeMessage(impl.getBaseBranch() + " does not contain the cleanup commit " + run.getCommitSha().substring(0, 7));
            save(run, logText, "Not dropping the columns: " + run.getMergeMessage());
            throw new InvalidStateException(run.getMergeMessage() + ". Retry the merge, deploy it, then drop the columns.");
        }
        try {
            List<String> dropped = updateDatabases(columns, true, logText);
            run.setColumnsStatus(FinalizeRun.DROPPED);
            run.setDroppedColumns(String.join("\n", dropped));
            run.setDroppedAt(Instant.now());
            run.setDroppedBy(userId);
            run.setErrorMessage(null);
            run = save(run, logText, "Plain-text columns dropped: " + (dropped.isEmpty() ? "none left" : String.join(", ", dropped)));
        } catch (SQLException | RuntimeException e) {
            log.warn("Finalize column drop failed for workspace {}: {}", workspaceId, e.getMessage());
            run.setColumnsStatus(FinalizeRun.FAILED);
            run.setErrorMessage(truncate(describe(e), 2000));
            save(run, logText, "Failed: " + describe(e));
            throw new InvalidStateException("Could not drop the plain-text columns: " + describe(e));
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("columns", run.getDroppedColumns());
        auditService.record(workspaceId, null, userId, null, "FINALIZE_COLUMNS_DROPPED", "IMPLEMENTATION",
            run.getId().toString(), impl.getBranchName(), "SUCCESS", Severity.MEDIUM, null, null, metadata);
        lifecycleService.advance(workspaceId, userId, true);
        return buildStatus(workspaceId);
    }

    /**
     * Release failed: closes the release pull request without merging (the base branch is the version to redeploy),
     * removes PI Co-Pilot's rows from the Flyway history so the base branch starts against the database, optionally
     * drops the encrypted columns, then completes the lifecycle.
     *
     * @param dropEncrypted also drop the {@code _enc} columns; otherwise they stay, unused by the base branch
     */
    public Status releaseFailed(UUID workspaceId, boolean dropEncrypted, String userId) {
        workspaceService.requireRole(workspaceId, userId, UserRole.PLATFORM_ADMIN, UserRole.APP_OWNER);
        Status status = buildStatus(workspaceId);
        if (!status.canChooseOutcome()) {
            throw new InvalidStateException(!status.problems().isEmpty() ? String.join("; ", status.problems())
                : "The release outcome is already recorded (" + status.releaseOutcome() + ")");
        }
        ImplementationRun impl = approvedImplementation(workspaceId);
        List<Column> columns = columns(impl, new ArrayList<>());
        FinalizeRun run = current(workspaceId, impl);
        if (run == null) run = new FinalizeRun();
        run.setWorkspaceId(workspaceId);
        run.setImplementationRunId(impl.getId());
        run.setReleaseOutcome(FinalizeRun.FAILED);
        run.setErrorMessage(null);
        StringBuilder logText = new StringBuilder(run.getLog() == null ? "" : run.getLog());
        run = save(run, logText, "Release failed: rolling back to " + impl.getBaseBranch());
        try {
            PullRequestGateway.Repo repo = pullRequests.repo(impl.getRepositoryConnectorId());
            pullRequests.comment(repo, impl.getPrNumber(), "**Release failed** (reported in PI Co-Pilot Finalize). "
                + "This pull request is closed without merging; redeploy `" + impl.getBaseBranch() + "`.");
            pullRequests.close(repo, impl.getPrNumber());
            impl.setMergeStatus(FinalizeRun.CLOSED);
            impl.setMergeMessage("Release failed: closed without merging into " + impl.getBaseBranch());
            implementationRunRepository.save(impl);
            run.setMergeStatus(FinalizeRun.CLOSED);
            run.setMergeMessage(impl.getMergeMessage());
            run = save(run, logText, impl.getPrNumber() == null ? "No pull request to close; " + impl.getBranchName()
                + " is left unmerged" : "Closed pull request #" + impl.getPrNumber() + " without merging");
            List<String> dropped = rollBackDatabases(columns, dropEncrypted, logText);
            run.setColumnsStatus(dropEncrypted ? FinalizeRun.DROPPED : FinalizeRun.KEPT);
            run.setDroppedColumns(dropped.isEmpty() ? null : String.join("\n", dropped));
            if (dropEncrypted) {
                run.setDroppedAt(Instant.now());
                run.setDroppedBy(userId);
            }
            run = save(run, logText, dropEncrypted
                ? "Encrypted columns dropped: " + (dropped.isEmpty() ? "none left" : String.join(", ", dropped))
                : "Encrypted columns kept; the base branch does not use them");
        } catch (SQLException | RuntimeException e) {
            log.warn("Release rollback failed for workspace {}: {}", workspaceId, e.getMessage());
            run.setErrorMessage(truncate(describe(e), 2000));
            save(run, logText, "Failed: " + describe(e));
            throw new InvalidStateException("Could not complete the rollback: " + describe(e));
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("encryptedColumnsDropped", dropEncrypted);
        metadata.put("columns", run.getDroppedColumns());
        auditService.record(workspaceId, null, userId, null, "FINALIZE_RELEASE_FAILED", "IMPLEMENTATION",
            run.getId().toString(), impl.getBranchName(), "SUCCESS", dropEncrypted ? Severity.MEDIUM : Severity.INFO,
            null, null, metadata);
        lifecycleService.advance(workspaceId, userId, true);
        return buildStatus(workspaceId);
    }

    private Status buildStatus(UUID workspaceId) {
        WorkspaceStage stage = workspaceRepository.findById(workspaceId).orElseThrow().getLifecycleStage();
        ImplementationRun impl = approvedImplementation(workspaceId);
        FinalizeRun run = current(workspaceId, impl);
        String outcome = run == null ? null : run.getReleaseOutcome();
        boolean finished = run != null && (FinalizeRun.DROPPED.equals(run.getColumnsStatus())
            || FinalizeRun.FAILED.equals(outcome) && run.getColumnsStatus() != null
                && !FinalizeRun.PENDING.equals(run.getColumnsStatus()) && !FinalizeRun.FAILED.equals(run.getColumnsStatus()));
        boolean merged = run != null && FinalizeRun.COMPLETED.equals(run.getCodeStatus())
            && FinalizeRun.MERGED.equals(run.getMergeStatus());
        boolean running = run != null && FinalizeRun.RUNNING.equals(run.getCodeStatus());

        List<String> problems = new ArrayList<>();
        List<Column> columns = List.of();
        if (!finished && stage != WorkspaceStage.FINALIZE) {
            problems.add("Finalize runs at the Finalize stage (now: " + stage + ")");
        }
        if (impl == null) {
            problems.add("Approve an implementation in Final Review first");
        } else {
            columns = columns(impl, problems);
        }
        boolean ready = problems.isEmpty() && !finished && !running;
        boolean succeeded = FinalizeRun.SUCCEEDED.equals(outcome);
        return new Status(stage, impl == null ? null : impl.getId(), impl == null ? null : impl.getBaseBranch(),
            impl == null ? null : impl.getBranchName(), impl == null ? null : impl.getPrNumber(),
            impl == null ? null : impl.getPrUrl(),
            columns.stream().map(c -> c.schema() + "." + c.table() + "." + c.column()).toList(), outcome, problems,
            ready && (outcome == null || succeeded && !FinalizeRun.COMPLETED.equals(run.getCodeStatus())),
            ready && (outcome == null || succeeded) && !merged,
            ready && succeeded && merged,
            run == null ? null : run.getCodeStatus(), run == null ? null : run.getCommitSha(),
            run == null ? null : run.getMergeStatus(), run == null ? null : run.getMergeMessage(),
            lines(run == null ? null : run.getChangedFiles()), lines(run == null ? null : run.getWarnings()),
            run == null ? null : run.getColumnsStatus(), lines(run == null ? null : run.getDroppedColumns()),
            run == null ? null : run.getDroppedAt(), run == null ? null : run.getDroppedBy(),
            run == null ? null : run.getErrorMessage(), lines(run == null ? null : run.getLog()));
    }

    /** Reads the release branch and builds the cleanup; throws if a column cannot be moved off its plain column. */
    private Cleanup prepareCode(PullRequestGateway.Repo repo, ImplementationRun impl, List<Column> columns,
                                StringBuilder logText) {
        String base = impl.getBranchName();
        String baseSha = github.get(repo.apiBase(), repo.token(), repo.repoPath() + "/git/ref/heads/"
            + PullRequestGateway.encodePath(base)).path("object").path("sha").asText();
        String baseTree = github.get(repo.apiBase(), repo.token(), repo.repoPath() + "/git/commits/" + baseSha)
            .path("tree").path("sha").asText();
        appendLog(logText, "Reading " + repo.owner() + "/" + repo.name() + " at " + base + " (" + baseSha.substring(0, 7) + ")");
        JsonNode tree = github.get(repo.apiBase(), repo.token(), repo.repoPath() + "/git/trees/" + baseTree + "?recursive=1");
        Map<String, String> files = new LinkedHashMap<>();
        for (JsonNode node : tree.path("tree")) {
            if ("blob".equals(node.path("type").asText())) files.put(node.path("path").asText(), node.path("sha").asText());
        }
        List<String> includes = repo.includes();
        Predicate<String> inScope = p -> !p.contains("/test/")
            && (includes.isEmpty() || includes.stream().anyMatch(i -> p.startsWith(i.endsWith("/") ? i : i + "/")));

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> changes = new LinkedHashMap<>();
        Map<String, String> kinds = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>(); // every file read, as it will be after the cleanup

        // The converter goes next to the application's PiFieldCipher, which it uses
        String cipherPath = files.keySet().stream()
            .filter(p -> p.endsWith("/" + EncryptionCodeTemplates.CIPHER_CLASS + ".java") && inScope.test(p))
            .findFirst().orElseThrow(() -> new IllegalStateException(EncryptionCodeTemplates.CIPHER_CLASS
                + " was not found on " + base + "; the release branch must contain the approved implementation"));
        String securityPkg = JpaEntityAnalyzer.packageName(readBlob(repo, files.get(cipherPath)));
        String securityDir = cipherPath.substring(0, cipherPath.lastIndexOf('/') + 1);
        String converter = (securityPkg.isEmpty() ? "" : securityPkg + ".") + EncryptionCodeTemplates.CONVERTER_CLASS;

        // Entities: each field moves to its encrypted column; the copy and the generated callbacks go away
        String persistence = "jakarta.persistence";
        String moduleRoot = null;
        Map<String, List<String>> fieldsByEntity = new LinkedHashMap<>();
        Map<String, List<Column>> byEntityFile = columns.stream()
            .collect(Collectors.groupingBy(Column::entityFile, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<Column>> entry : byEntityFile.entrySet()) {
            String path = entry.getKey();
            if (!files.containsKey(path)) {
                blockers.add(path + " (the entity of " + entry.getValue().get(0).table() + ") is not on " + base);
                continue;
            }
            String source = readBlob(repo, files.get(path));
            if (source.contains("javax.persistence")) persistence = "javax.persistence";
            if (moduleRoot == null) moduleRoot = moduleRoot(path);
            Map<String, String> fields = new LinkedHashMap<>();
            entry.getValue().forEach(c -> fields.put(c.column(), c.field()));
            String updated = JpaEntityAnalyzer.withPlainColumnsRemoved(source, fields, converter);
            for (Column c : entry.getValue()) {
                if (!JpaEntityAnalyzer.mapsEncryptedColumn(updated, c.field(), c.column())) {
                    blockers.add(path + ": field " + c.field() + " could not be moved to " + c.column()
                        + JpaEntityAnalyzer.ENCRYPTED_SUFFIX + " automatically");
                }
            }
            sources.put(path, updated);
            if (!updated.equals(source)) {
                changes.put(path, updated);
                kinds.put(path, "MODIFIED");
                appendLog(logText, "Moved " + String.join(", ", fields.values()) + " to the encrypted columns in " + path);
            }
            String className = JpaEntityAnalyzer.className(source);
            if (className != null) {
                fieldsByEntity.put(className.substring(className.lastIndexOf('.') + 1), List.copyOf(fields.values()));
            }
        }
        if (moduleRoot == null) moduleRoot = "";

        // Repositories: native queries read the encrypted columns; queries on encrypted fields are reported
        Map<String, List<String>> columnsByTable = new LinkedHashMap<>();
        columns.forEach(c -> columnsByTable.computeIfAbsent(c.table(), t -> new ArrayList<>()).add(c.column()));
        boolean nativeQueries = false;
        List<String> repositories = files.keySet().stream()
            .filter(p -> p.endsWith(".java") && inScope.test(p))
            .filter(p -> p.endsWith("Repository.java") || p.endsWith("Dao.java") || p.contains("/repository/"))
            .limit(MAX_FILE_FETCHES)
            .toList();
        for (String path : repositories) {
            String source = readBlob(repo, files.get(path));
            String name = fileName(path);
            String updated = source;
            if (NativeQueryEncryptor.hasNativeQuery(source)) {
                NativeQueryEncryptor.FinalizeResult r = NativeQueryEncryptor.finalizeQueries(source, columnsByTable);
                r.blockers().forEach(b -> blockers.add(name + "." + b));
                updated = r.source();
                nativeQueries |= updated.contains(JpaEntityAnalyzer.ENCRYPTED_SUFFIX);
            }
            fieldsByEntity.forEach((entity, fields) -> NativeQueryEncryptor.encryptedFieldQueries(source, entity, fields)
                .forEach(w -> warnings.add(name + "." + w)));
            sources.put(path, updated);
            if (!updated.equals(source)) {
                changes.put(path, updated);
                kinds.put(path, "MODIFIED");
                appendLog(logText, "Native queries in " + path + " now read only the encrypted columns");
            }
        }

        // Generated classes: the converter and decryptor are on the branch already; older branches may lack them
        String converterPath = securityDir + EncryptionCodeTemplates.CONVERTER_CLASS + ".java";
        if (!files.containsKey(converterPath)) {
            changes.put(converterPath, EncryptionCodeTemplates.converter(securityPkg, persistence));
            kinds.put(converterPath, "ADDED");
            appendLog(logText, "Adding " + EncryptionCodeTemplates.CONVERTER_CLASS);
        }
        boolean hadRouter = files.keySet().stream().anyMatch(p -> p.endsWith("/PiNativeQueryRouter.java"));
        String decryptorPath = securityDir + EncryptionCodeTemplates.DECRYPTOR_CLASS + ".java";
        if ((nativeQueries || hadRouter) && !files.containsKey(decryptorPath)) {
            changes.put(decryptorPath, EncryptionCodeTemplates.nativeQueryDecryptor(securityPkg));
            kinds.put(decryptorPath, "ADDED");
            appendLog(logText, "Adding " + EncryptionCodeTemplates.DECRYPTOR_CLASS);
        }
        Set<String> retired = new HashSet<>();
        for (String cls : RETIRED_CLASSES) {
            for (String path : files.keySet().stream().filter(p -> p.endsWith("/" + cls + ".java") && inScope.test(p)).toList()) {
                String content = readBlob(repo, files.get(path));
                if (!content.toLowerCase(Locale.ROOT).contains("generated by pi co-pilot")) {
                    appendLog(logText, "Keeping " + path + ": it was not generated by PI Co-Pilot");
                    continue;
                }
                changes.put(path, null);
                kinds.put(path, "DELETED");
                retired.add(cls);
                appendLog(logText, "Deleting " + path);
            }
        }
        for (String cls : retired) {
            sources.forEach((path, content) -> {
                if (content != null && Pattern.compile("\\b" + cls + "\\b").matcher(content).find()) {
                    blockers.add(path + " still uses " + cls + ", which Finalize removes");
                }
            });
        }

        addDropMigration(repo, files, moduleRoot, columns, changes, kinds, logText);

        if (!blockers.isEmpty()) {
            throw new IllegalStateException(String.join("; ", blockers));
        }
        ImplementationExecutor.requireWithinIncludes(changes.keySet(), includes);
        warnings.forEach(w -> appendLog(logText, "Warning: " + w));
        if (changes.isEmpty()) appendLog(logText, "The release branch already has the cleanup");
        return new Cleanup(baseSha, baseTree, changes, kinds, List.copyOf(new java.util.LinkedHashSet<>(warnings)));
    }

    /**
     * Adds a Flyway migration dropping the plain columns that no earlier PI Co-Pilot drop migration covers, so a
     * column approved in a later round is still retired by the application's own migrations.
     */
    private void addDropMigration(PullRequestGateway.Repo repo, Map<String, String> files, String moduleRoot,
                                  List<Column> columns, Map<String, String> changes, Map<String, String> kinds,
                                  StringBuilder logText) {
        String dir = null;
        int max = 0;
        StringBuilder earlier = new StringBuilder();
        for (Map.Entry<String, String> file : files.entrySet()) {
            Matcher m = FLYWAY_FILE.matcher(file.getKey());
            if (m.matches() && file.getKey().startsWith(moduleRoot)) {
                if (file.getKey().endsWith(DROP_MIGRATION)) earlier.append(readBlob(repo, file.getValue())).append('\n');
                dir = m.group(1);
                max = Math.max(max, Integer.parseInt(m.group(2)));
            }
        }
        if (dir == null) {
            appendLog(logText, "No Flyway migrations in the repository; the columns are dropped only in the database");
            return;
        }
        List<EncryptionCodeTemplates.PlainColumn> missing = columns.stream()
            .map(c -> new EncryptionCodeTemplates.PlainColumn(c.schema(), c.table(), c.column()))
            .distinct()
            // generated earlier: with a marker per column, or (older versions) a plain DROP COLUMN statement
            .filter(c -> !earlier.toString().contains(c.marker()) && !earlier.toString().contains(
                quote(c.table()) + " DROP COLUMN IF EXISTS " + quote(c.column())))
            .toList();
        if (missing.isEmpty()) {
            appendLog(logText, "An earlier Flyway migration already drops these plain columns");
            return;
        }
        String path = dir + "V" + (max + 1) + DROP_MIGRATION;
        changes.put(path, EncryptionCodeTemplates.dropMigration(missing));
        kinds.put(path, "ADDED");
        appendLog(logText, "Added Flyway migration " + path);
    }

    /** Commits the cleanup on top of the release branch, so its pull request carries it; nothing to do if it is there. */
    private FinalizeRun push(FinalizeRun run, PullRequestGateway.Repo repo, ImplementationRun impl, List<Column> columns,
                             Cleanup cleanup, StringBuilder logText) {
        run.setBranchName(impl.getBranchName());
        run.setPrNumber(impl.getPrNumber());
        run.setPrUrl(impl.getPrUrl());
        run.setWarnings(cleanup.warnings().isEmpty() ? null : String.join("\n", cleanup.warnings()));
        if (cleanup.changes().isEmpty()) {
            run.setCommitSha(cleanup.headSha());
            return save(run, logText, null);
        }
        ArrayNode entries = objectMapper.createArrayNode();
        cleanup.changes().forEach((path, content) -> {
            ObjectNode entry = entries.addObject();
            entry.put("path", path);
            entry.put("mode", "100644");
            entry.put("type", "blob");
            if (content == null) entry.putNull("sha"); // deletes the file
            else entry.put("content", content);
        });
        ObjectNode treeRequest = objectMapper.createObjectNode();
        treeRequest.put("base_tree", cleanup.headTree());
        treeRequest.set("tree", entries);
        String newTree = github.post(repo.apiBase(), repo.token(), repo.repoPath() + "/git/trees", treeRequest)
            .path("sha").asText();
        String message = "PI Co-Pilot: retire the plain-text PI columns after a successful release\n\n"
            + columns.stream().map(c -> "- " + c.table() + "." + c.column() + " -> " + c.column() + JpaEntityAnalyzer.ENCRYPTED_SUFFIX)
                .distinct().collect(Collectors.joining("\n"))
            + "\n\nEntity fields now map to the encrypted columns through " + EncryptionCodeTemplates.CONVERTER_CLASS + ";"
            + "\nnative queries read only the encrypted columns. Deploy this before PI Co-Pilot drops the plain columns.";
        String commitSha = github.post(repo.apiBase(), repo.token(), repo.repoPath() + "/git/commits",
            Map.of("message", message, "tree", newTree, "parents", List.of(cleanup.headSha()))).path("sha").asText();
        github.patch(repo.apiBase(), repo.token(), repo.repoPath() + "/git/refs/heads/"
            + PullRequestGateway.encodePath(impl.getBranchName()), Map.of("sha", commitSha, "force", false));
        appendLog(logText, "Pushed cleanup commit " + commitSha.substring(0, 7) + " to " + impl.getBranchName());
        run.setCommitSha(commitSha);
        run.setChangedFiles(cleanup.changes().keySet().stream().map(p -> cleanup.kinds().get(p) + "|" + p)
            .collect(Collectors.joining("\n")));
        pullRequests.comment(repo, impl.getPrNumber(), pullRequestComment(cleanup, columns));
        return save(run, logText, null);
    }

    /** Merges the release pull request into the base branch and records the outcome on both runs (never throws). */
    private FinalizeRun merge(FinalizeRun run, ImplementationRun impl, StringBuilder logText) {
        try {
            String sha = pullRequests.merge(pullRequests.repo(impl.getRepositoryConnectorId()), impl.getPrNumber(),
                impl.getBranchName(), impl.getBaseBranch(), "PI Co-Pilot: encrypt personal data (release succeeded)",
                run.getCommitSha());
            run.setMergeStatus(FinalizeRun.MERGED);
            run.setMergeSha(sha);
            run.setMergeMessage("Merged " + impl.getBranchName() + " into " + impl.getBaseBranch());
            impl.setMergeStatus(ImplementationRun.MERGED);
            impl.setMergeSha(sha);
            impl.setMergeMessage(run.getMergeMessage());
            impl.setMergedAt(Instant.now());
            implementationRunRepository.save(impl);
            return save(run, logText, run.getMergeMessage() + ". Deploy it, then drop the plain-text columns.");
        } catch (RuntimeException e) {
            run.setMergeStatus(FinalizeRun.FAILED);
            run.setMergeMessage(describe(e));
            return save(run, logText, "Could not merge the release: " + describe(e));
        }
    }

    private static String pullRequestComment(Cleanup cleanup, List<Column> columns) {
        StringBuilder body = new StringBuilder("**Release succeeded** (reported in PI Co-Pilot Finalize). PI Co-Pilot ")
            .append("pushed a cleanup so the application stops using the plain-text PI columns, and merges this pull ")
            .append("request next.\n\n| Column | Now stored only in |\n|---|---|\n");
        columns.stream().map(c -> "| `" + c.table() + "." + c.column() + "` | `" + c.column() + JpaEntityAnalyzer.ENCRYPTED_SUFFIX + "` |\n")
            .distinct().forEach(body::append);
        body.append("\n| File | Change |\n|---|---|\n");
        cleanup.changes().keySet().forEach(p -> body.append("| `").append(p).append("` | ").append(cleanup.kinds().get(p)).append(" |\n"));
        if (!cleanup.warnings().isEmpty()) {
            body.append("\n**Queries on encrypted fields** (they still run but can no longer match values):\n");
            cleanup.warnings().forEach(w -> body.append("- ").append(w).append('\n'));
        }
        return body.append("\n**Deploy the merged code before the plain columns are dropped** in PI Co-Pilot's Finalize step.")
            .toString();
    }

    /**
     * Per database, in one transaction: locks each table against writes, brings every encrypted copy up to date with
     * its plain value, then makes the plain columns nullable (before the cleaned-up code, which no longer writes them,
     * is deployed) or drops them.
     *
     * @return the columns dropped, as schema.table.column
     */
    private List<String> updateDatabases(List<Column> columns, boolean drop, StringBuilder logText) throws SQLException {
        List<String> dropped = new ArrayList<>();
        Map<UUID, List<Column>> byDb = columns.stream()
            .collect(Collectors.groupingBy(Column::connectorId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<UUID, List<Column>> entry : byDb.entrySet()) {
            String label = entry.getValue().get(0).database();
            DatabaseConnector connector = dbConnectorRepository.findById(entry.getKey()).orElse(null);
            if (connector == null) throw new IllegalStateException("The database connector for " + label + " no longer exists");
            Map<String, List<Column>> byTable = entry.getValue().stream()
                .collect(Collectors.groupingBy(c -> c.schema() + "\u0000" + c.table(), LinkedHashMap::new, Collectors.toList()));
            try (Connection conn = dbConnectorService.openWriteConnection(connector)) {
                try (Statement st = conn.createStatement()) {
                    st.setQueryTimeout(300);
                    for (List<Column> tableColumns : byTable.values()) {
                        String schema = tableColumns.get(0).schema();
                        String tableName = tableColumns.get(0).table();
                        String table = quote(schema) + "." + quote(tableName);
                        Set<String> existing = existingColumns(conn, schema, tableName);
                        List<String> plain = tableColumns.stream().map(Column::column).distinct().filter(existing::contains).toList();
                        for (String c : plain) {
                            if (!existing.contains(c + JpaEntityAnalyzer.ENCRYPTED_SUFFIX)) {
                                throw new IllegalStateException(schema + "." + tableName + "." + c + JpaEntityAnalyzer.ENCRYPTED_SUFFIX
                                    + " does not exist; the plain column is kept");
                            }
                        }
                        if (plain.isEmpty()) {
                            appendLog(logText, schema + "." + tableName + ": plain columns already dropped");
                            continue;
                        }
                        st.execute("LOCK TABLE " + table + " IN SHARE ROW EXCLUSIVE MODE");
                        long refreshed = syncCopies(conn, table, plain);
                        appendLog(logText, schema + "." + tableName + ": every encrypted copy checked"
                            + (refreshed > 0 ? ", " + refreshed + " row(s) re-encrypted from the plain value" : ""));
                        for (String c : plain) {
                            st.execute("ALTER TABLE " + table + (drop ? " DROP COLUMN IF EXISTS " + quote(c)
                                : " ALTER COLUMN " + quote(c) + " DROP NOT NULL"));
                            if (drop) dropped.add(schema + "." + tableName + "." + c);
                        }
                        appendLog(logText, schema + "." + tableName + ": " + String.join(", ", plain)
                            + (drop ? " dropped" : " made nullable, so the cleaned-up code can insert rows without them"));
                    }
                    conn.commit();
                } catch (SQLException | RuntimeException e) {
                    conn.rollback();
                    if (e instanceof SQLException sql) {
                        throw new SQLException("In " + label + ": " + firstLine(sql.getMessage())
                            + (drop ? "" : ". The database user needs ALTER permission on these tables."), sql);
                    }
                    throw e;
                }
            }
        }
        return dropped;
    }

    /**
     * After a failed release, per database: removes PI Co-Pilot's rows from the Flyway history (the base branch does
     * not have those migrations, so Flyway would refuse to start it) and optionally drops the encrypted columns.
     *
     * @return the encrypted columns dropped, as schema.table.column
     */
    private List<String> rollBackDatabases(List<Column> columns, boolean dropEncrypted, StringBuilder logText) throws SQLException {
        List<String> dropped = new ArrayList<>();
        Map<UUID, List<Column>> byDb = columns.stream()
            .collect(Collectors.groupingBy(Column::connectorId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<UUID, List<Column>> entry : byDb.entrySet()) {
            String label = entry.getValue().get(0).database();
            DatabaseConnector connector = dbConnectorRepository.findById(entry.getKey()).orElse(null);
            if (connector == null) throw new IllegalStateException("The database connector for " + label + " no longer exists");
            try (Connection conn = dbConnectorService.openWriteConnection(connector)) {
                try (Statement st = conn.createStatement()) {
                    st.setQueryTimeout(300);
                    for (String schema : entry.getValue().stream().map(Column::schema).distinct().toList()) {
                        if (existingColumns(conn, schema, "flyway_schema_history").isEmpty()) continue;
                        try (PreparedStatement del = conn.prepareStatement("DELETE FROM " + quote(schema)
                            + ".flyway_schema_history WHERE script LIKE ?")) {
                            del.setString(1, "%" + FLYWAY_SCRIPT_MARKER + "%");
                            int rows = del.executeUpdate();
                            if (rows > 0) appendLog(logText, label + ": removed " + rows + " PI Co-Pilot migration(s) from "
                                + schema + ".flyway_schema_history, so " + "the base branch starts");
                        }
                    }
                    if (dropEncrypted) {
                        for (Column c : entry.getValue()) {
                            String enc = c.column() + JpaEntityAnalyzer.ENCRYPTED_SUFFIX;
                            if (!existingColumns(conn, c.schema(), c.table()).contains(enc)) continue;
                            st.execute("ALTER TABLE " + quote(c.schema()) + "." + quote(c.table())
                                + " DROP COLUMN IF EXISTS " + quote(enc));
                            dropped.add(c.schema() + "." + c.table() + "." + enc);
                        }
                    }
                    conn.commit();
                } catch (SQLException | RuntimeException e) {
                    conn.rollback();
                    if (e instanceof SQLException sql) {
                        throw new SQLException("In " + label + ": " + firstLine(sql.getMessage()), sql);
                    }
                    throw e;
                }
            }
        }
        return dropped;
    }

    /** Re-encrypts every copy that is missing or no longer decrypts to its plain value; the table is locked. */
    private long syncCopies(Connection conn, String table, List<String> plain) throws SQLException {
        StringBuilder select = new StringBuilder("SELECT ctid::text");
        for (String c : plain) {
            select.append(", ").append(quote(c)).append("::text, ").append(quote(c + JpaEntityAnalyzer.ENCRYPTED_SUFFIX));
        }
        select.append(" FROM ").append(table);
        List<String[]> stale = new ArrayList<>(); // ctid, then the encrypted value per column
        try (Statement st = conn.createStatement()) {
            st.setFetchSize(1000);
            try (ResultSet rs = st.executeQuery(select.toString())) {
                while (rs.next()) {
                    String[] row = new String[plain.size() + 1];
                    row[0] = rs.getString(1);
                    boolean changed = false;
                    for (int i = 0; i < plain.size(); i++) {
                        String value = rs.getString(2 + i * 2);
                        String enc = rs.getString(3 + i * 2);
                        boolean current = value == null ? enc == null
                            : enc != null && cipher.isEncrypted(enc) && Objects.equals(value, decrypt(enc));
                        row[1 + i] = current ? enc : value == null ? null : cipher.encrypt(value);
                        changed |= !current;
                    }
                    if (changed) stale.add(row);
                }
            }
        }
        if (stale.isEmpty()) return 0;
        String update = "UPDATE " + table + " SET " + plain.stream()
            .map(c -> quote(c + JpaEntityAnalyzer.ENCRYPTED_SUFFIX) + " = ?").collect(Collectors.joining(", "))
            + " WHERE ctid = ?::tid";
        try (PreparedStatement up = conn.prepareStatement(update)) {
            for (int r = 0; r < stale.size(); r++) {
                String[] row = stale.get(r);
                for (int i = 0; i < plain.size(); i++) up.setString(1 + i, row[1 + i]);
                up.setString(plain.size() + 1, row[0]);
                up.addBatch();
                if ((r + 1) % BATCH_SIZE == 0) up.executeBatch();
            }
            up.executeBatch();
        }
        return stale.size();
    }

    private static Set<String> existingColumns(Connection conn, String schema, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (PreparedStatement st = conn.prepareStatement(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?")) {
            st.setString(1, schema);
            st.setString(2, table);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) names.add(rs.getString(1));
            }
        }
        return names;
    }

    private String decrypt(String enc) {
        try {
            return cipher.decrypt(enc);
        } catch (RuntimeException e) {
            return null; // re-encrypted from the plain value
        }
    }

    /** The columns mapped to an entity field; any other approved column is a problem, as its code still reads it plain. */
    private List<Column> columns(ImplementationRun impl, List<String> problems) {
        List<Map<String, Object>> raw;
        try {
            raw = impl.getColumnsJson() == null ? List.of()
                : objectMapper.readValue(impl.getColumnsJson(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("The approved implementation has unreadable column data", e);
        }
        List<DatabaseConnector> connectors = dbConnectorRepository.findByWorkspaceIdOrderByCreatedAtDesc(impl.getWorkspaceId());
        List<Column> columns = new ArrayList<>();
        for (Map<String, Object> c : raw) {
            String database = String.valueOf(c.get("database"));
            String name = c.get("table") + "." + c.get("column");
            String code = String.valueOf(c.get("codeStatus"));
            // RETIRED: an earlier release already moved the field onto its _enc column; only the plain column is left
            String prefix = code.startsWith(JpaEntityAnalyzer.RETIRED + ":") ? JpaEntityAnalyzer.RETIRED : JpaEntityAnalyzer.MAPPED;
            if (!code.startsWith(prefix + ":") || c.get("entityFile") == null) {
                problems.add(name + ": the code was not updated in Implementation (" + code
                    + "), so dropping the plain column would break it");
                continue;
            }
            UUID connectorId = c.get("connectorId") != null ? UUID.fromString(String.valueOf(c.get("connectorId")))
                // Runs from before connector ids were recorded only kept the "host:port/db" label
                : connectors.stream().filter(dc -> label(dc).equals(database)).map(DatabaseConnector::getId)
                    .findFirst().orElse(null);
            if (connectorId == null) {
                problems.add(name + ": the database connector for " + database + " no longer exists");
                continue;
            }
            columns.add(new Column(connectorId, database, String.valueOf(c.get("schema")), String.valueOf(c.get("table")),
                String.valueOf(c.get("column")), code.substring(prefix.length() + 1),
                String.valueOf(c.get("entityFile"))));
        }
        return columns;
    }

    /** The most recent implementation round approved in Final Review. */
    private ImplementationRun approvedImplementation(UUID workspaceId) {
        return implementationRunRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
            .filter(r -> ImplementationRun.APPROVED.equals(r.getReviewStatus()))
            .findFirst().orElse(null);
    }

    /** The latest Finalize run of the approved implementation, or null. */
    private FinalizeRun current(UUID workspaceId, ImplementationRun impl) {
        FinalizeRun run = runRepository.findFirstByWorkspaceIdOrderByCreatedAtDesc(workspaceId).orElse(null);
        return run == null || impl == null || !run.getImplementationRunId().equals(impl.getId()) ? null : run;
    }

    private String readBlob(PullRequestGateway.Repo repo, String sha) {
        String content = github.get(repo.apiBase(), repo.token(), repo.repoPath() + "/git/blobs/" + sha).path("content").asText("");
        return new String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8);
    }

    private FinalizeRun save(FinalizeRun run, StringBuilder logText, String line) {
        if (line != null) appendLog(logText, line);
        run.setLog(logText.toString());
        // save() returns the entity with its new @Version; always continue with the returned instance
        return runRepository.save(run);
    }

    private static void appendLog(StringBuilder logText, String line) {
        logText.append(Instant.now()).append(' ').append(line).append('\n');
    }

    private static List<String> lines(String text) {
        return text == null || text.isBlank() ? List.of() : text.lines().toList();
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
    }

    private static String moduleRoot(String path) {
        int i = path.indexOf("src/main/java/");
        return i < 0 ? "" : path.substring(0, i);
    }

    private static String label(DatabaseConnector c) {
        return c.getHost() + ":" + c.getPort() + "/" + c.getDatabaseName();
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : firstLine(e.getMessage());
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        int i = message.indexOf('\n');
        return i < 0 ? message : message.substring(0, i);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
