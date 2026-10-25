package com.picopilot.implementation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picopilot.common.crypto.SecretCipher;
import com.picopilot.common.event.StageWorkCompletedEvent;
import com.picopilot.common.model.WorkspaceStage;
import com.picopilot.implementation.model.ImplementationFileChange;
import com.picopilot.implementation.model.ImplementationRun;
import com.picopilot.implementation.repository.ImplementationFileChangeRepository;
import com.picopilot.implementation.repository.ImplementationRunRepository;
import com.picopilot.inventory.model.PiFinding;
import com.picopilot.inventory.repository.PiFindingRepository;
import com.picopilot.project.database.DatabaseConnectorService;
import com.picopilot.project.github.GitHubApiClient;
import com.picopilot.project.model.DatabaseConnector;
import com.picopilot.project.model.RepositoryConnector;
import com.picopilot.project.repository.DatabaseConnectorRepository;
import com.picopilot.project.repository.RepositoryConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.picopilot.project.github.GitHubApiClient.encode;

/**
 * Runs an Encryption Implementation: adds {@code <column>_enc} columns to every database with
 * approved PI, then creates a branch from the connector's branch with the entity changes,
 * the cipher/converter (if the repository has none), key configuration and a Flyway migration,
 * committed in one commit through the GitHub Git Data API.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImplementationExecutor {

    private static final int MAX_FILE_FETCHES = 150;
    private static final Pattern FLYWAY_FILE = Pattern.compile("(.*/db/migration/)V(\\d+)(?:_\\d+)*__[^/]+\\.sql$");
    private static final Pattern MODEL_DIR = Pattern.compile("/(entity|entities|model|models|domain)/");
    private static final DateTimeFormatter BRANCH_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ImplementationRunRepository runRepository;
    private final PiFindingRepository findingRepository;
    private final DatabaseConnectorRepository dbConnectorRepository;
    private final DatabaseConnectorService dbConnectorService;
    private final RepositoryConnectorRepository repoConnectorRepository;
    private final SecretCipher secretCipher;
    private final GitHubApiClient github;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final ImplementationFileChangeRepository fileChangeRepository;
    private final Environment environment;

    /** An approved column, with its encrypted twin and what happened to it in code. */
    private static final class ColumnPlan {
        final UUID connectorId;
        final String database;
        final String schema;
        final String table;
        final String column;
        String codeStatus = "PENDING";
        String entityFile;
        /** How reads switch to the encrypted column: ENTITY (PiEncryptionListener) or a NativeQueryEncryptor outcome. */
        String readStatus = "ENTITY";

        ColumnPlan(UUID connectorId, String database, String schema, String table, String column) {
            this.connectorId = connectorId;
            this.database = database;
            this.schema = schema;
            this.table = table;
            this.column = column;
        }

        String encryptedColumn() {
            return column + JpaEntityAnalyzer.ENCRYPTED_SUFFIX;
        }

        String ddl() {
            return "ALTER TABLE " + quote(schema) + "." + quote(table)
                + " ADD COLUMN IF NOT EXISTS " + quote(encryptedColumn()) + " text;";
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("connectorId", connectorId == null ? null : connectorId.toString());
            m.put("database", database);
            m.put("schema", schema);
            m.put("table", table);
            m.put("column", column);
            m.put("encryptedColumn", encryptedColumn());
            m.put("codeStatus", codeStatus);
            m.put("entityFile", entityFile);
            m.put("readStatus", readStatus);
            return m;
        }
    }

    private record RepoFile(String path, String sha) {}

    @Async
    public void execute(UUID runId) {
        ImplementationRun run = runRepository.findById(runId).orElse(null);
        if (run == null) return;
        StringBuilder logText = new StringBuilder();
        List<ColumnPlan> plans = new ArrayList<>();
        try {
            run.setStatus(ImplementationRun.RUNNING);
            run.setStartedAt(Instant.now());
            run = save(run, logText, "Implementation started");

            plans = plan(run);
            run = save(run, logText, "Approved scan has " + plans.size() + " PI column(s) to encrypt");

            run.setDbStatus(ImplementationRun.RUNNING);
            run = save(run, logText, null);
            run.setDdlScript(addColumns(plans, logText));
            run.setDbStatus(ImplementationRun.COMPLETED);
            run = save(run, logText, "Database step completed");

            run.setCodeStatus(ImplementationRun.RUNNING);
            run = save(run, logText, null);
            run = changeCode(run, plans, logText);
            run.setCodeStatus(ImplementationRun.COMPLETED);

            run.setStatus(ImplementationRun.COMPLETED);
            run.setReviewStatus(ImplementationRun.PENDING_REVIEW);
            run.setCompletedAt(Instant.now());
            run.setColumnsJson(columnsJson(plans));
            run = save(run, logText, "Implementation completed. Branch " + run.getBranchName() + " is ready for deployment.");
            events.publishEvent(new StageWorkCompletedEvent(run.getWorkspaceId(), WorkspaceStage.IMPLEMENTATION,
                run.getCreatedBy() != null ? run.getCreatedBy() : "system"));
        } catch (Exception e) {
            log.warn("Implementation run {} failed: {}", runId, e.getMessage());
            if (ImplementationRun.RUNNING.equals(run.getDbStatus())) run.setDbStatus(ImplementationRun.FAILED);
            if (ImplementationRun.RUNNING.equals(run.getCodeStatus())) run.setCodeStatus(ImplementationRun.FAILED);
            run.setStatus(ImplementationRun.FAILED);
            run.setCompletedAt(Instant.now());
            run.setErrorMessage(truncate(describe(e), 2000));
            run.setColumnsJson(columnsJson(plans));
            save(run, logText, "Failed: " + describe(e));
        }
    }

    /** A server restart abandons running implementations; mark them failed so they can be retried. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedRuns() {
        // Queued review rounds (PENDING with a parent run) are picked up by the review scheduler, so keep them
        for (ImplementationRun run : runRepository.findByStatusIn(List.of(ImplementationRun.PENDING, ImplementationRun.RUNNING))) {
            if (ImplementationRun.PENDING.equals(run.getStatus()) && run.getParentRunId() != null) continue;
            run.setStatus(ImplementationRun.FAILED);
            run.setErrorMessage("Interrupted by a server restart. Start the implementation again.");
            run.setCompletedAt(Instant.now());
            runRepository.save(run);
        }
    }

    private List<ColumnPlan> plan(ImplementationRun run) {
        List<ColumnPlan> plans = new ArrayList<>();
        Map<UUID, String> labels = new LinkedHashMap<>();
        for (PiFinding f : findingRepository.findByScanJobIdOrderByDbSchemaAscTableNameAscColumnNameAsc(run.getScanJobId())) {
            if (!f.isApproved() || f.getDatabaseConnectorId() == null) continue;
            String label = labels.computeIfAbsent(f.getDatabaseConnectorId(), id -> dbConnectorRepository.findById(id)
                .map(c -> c.getHost() + ":" + c.getPort() + "/" + c.getDatabaseName()).orElse("deleted connector"));
            plans.add(new ColumnPlan(f.getDatabaseConnectorId(), label, f.getDbSchema(), f.getTableName(), f.getColumnName()));
        }
        if (plans.isEmpty()) {
            throw new IllegalStateException("The approved scan has no columns selected for encryption");
        }
        return plans;
    }

    private String addColumns(List<ColumnPlan> plans, StringBuilder logText) throws SQLException {
        List<String> ddl = new ArrayList<>();
        Map<UUID, List<ColumnPlan>> byDb = plans.stream()
            .collect(Collectors.groupingBy(p -> p.connectorId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<UUID, List<ColumnPlan>> entry : byDb.entrySet()) {
            DatabaseConnector connector = dbConnectorRepository.findById(entry.getKey())
                .orElseThrow(() -> new IllegalStateException("A database connector used by the scan no longer exists"));
            String label = entry.getValue().get(0).database;
            appendLog(logText, "Adding " + entry.getValue().size() + " encrypted column(s) in " + label);
            try (Connection connection = dbConnectorService.openWriteConnection(connector)) {
                try (Statement st = connection.createStatement()) {
                    st.setQueryTimeout(60);
                    // Runtime flags the application polls (encrypted-read cut-over)
                    st.execute(EncryptionCodeTemplates.FLAG_TABLE_DDL);
                    if (!ddl.contains(EncryptionCodeTemplates.FLAG_TABLE_DDL)) ddl.add(EncryptionCodeTemplates.FLAG_TABLE_DDL);
                    for (ColumnPlan p : entry.getValue()) {
                        st.execute(p.ddl());
                        ddl.add(p.ddl());
                    }
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw new SQLException("Could not add encrypted columns in " + label + ": " + firstLine(e.getMessage())
                        + ". The database user needs ALTER permission on these tables.", e);
                }
            }
        }
        return String.join("\n", ddl);
    }

    private ImplementationRun changeCode(ImplementationRun run, List<ColumnPlan> plans, StringBuilder logText) {
        RepositoryConnector repo = repoConnectorRepository.findById(run.getRepositoryConnectorId())
            .orElseThrow(() -> new IllegalStateException("The GitHub connector no longer exists"));
        String token = secretCipher.decrypt(repo.getEncryptedToken());
        String apiBase = repo.getApiBaseUrl() != null ? repo.getApiBaseUrl() : github.resolveApiBase(repo.getRepositoryUrl());
        String repoPath = "/repos/" + encode(repo.getRepositoryOwner()) + "/" + encode(repo.getRepositoryName());
        String baseBranch = repo.getBranch();

        JsonNode baseRef = github.get(apiBase, token, repoPath + "/git/ref/heads/" + encodePath(baseBranch));
        String baseSha = baseRef.path("object").path("sha").asText();
        String baseTree = github.get(apiBase, token, repoPath + "/git/commits/" + baseSha).path("tree").path("sha").asText();
        appendLog(logText, "Reading " + repo.getRepositoryOwner() + "/" + repo.getRepositoryName() + " at " + baseBranch
            + " (" + baseSha.substring(0, 7) + ")");

        JsonNode tree = github.get(apiBase, token, repoPath + "/git/trees/" + baseTree + "?recursive=1");
        if (tree.path("truncated").asBoolean()) {
            appendLog(logText, "Warning: the repository is very large; GitHub returned a partial file list");
        }
        List<String> includes = repo.getIncludePaths() == null ? List.of()
            : repo.getIncludePaths().lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        Map<String, RepoFile> files = new LinkedHashMap<>();
        for (JsonNode node : tree.path("tree")) {
            if ("blob".equals(node.path("type").asText())) {
                files.put(node.path("path").asText(), new RepoFile(node.path("path").asText(), node.path("sha").asText()));
            }
        }

        // Find the entity for each table among likely candidates
        Map<String, List<ColumnPlan>> byTable = plans.stream()
            .collect(Collectors.groupingBy(p -> p.table.toLowerCase(Locale.ROOT), LinkedHashMap::new, Collectors.toList()));
        List<RepoFile> candidates = files.values().stream()
            .filter(f -> f.path().endsWith(".java") && !f.path().contains("/test/"))
            .filter(f -> includes.isEmpty() || includes.stream().anyMatch(i -> f.path().startsWith(i.endsWith("/") ? i : i + "/")))
            .filter(f -> MODEL_DIR.matcher(f.path()).find() || byTable.keySet().stream().anyMatch(t -> nameMatches(f.path(), t)))
            .limit(MAX_FILE_FETCHES)
            .toList();
        Map<String, String> sources = new LinkedHashMap<>();
        for (RepoFile f : candidates) {
            String content = readBlob(apiBase, token, repoPath, f.sha());
            if (JpaEntityAnalyzer.isEntity(content)) sources.put(f.path(), content);
        }
        appendLog(logText, "Found " + sources.size() + " JPA entit" + (sources.size() == 1 ? "y" : "ies") + " to inspect");

        // Existing code is never edited. New classes go next to the repository's own PiFieldCipher if it has one,
        // otherwise into a "security" package beside the entities; they only act while encrypted read is on.
        Map<String, String> changes = new LinkedHashMap<>();
        Map<String, String> changeKinds = new LinkedHashMap<>();
        Map<String, String> originals = new LinkedHashMap<>();
        String securityPkg = null;
        String securityDir = null;
        RepoFile existingCipher = files.values().stream()
            .filter(f -> f.path().endsWith("/" + EncryptionCodeTemplates.CIPHER_CLASS + ".java") && !f.path().contains("/test/"))
            .filter(f -> includes.isEmpty() || includes.stream().anyMatch(i -> f.path().startsWith(i.endsWith("/") ? i : i + "/")))
            .findFirst().orElse(null);
        if (existingCipher != null) {
            securityPkg = JpaEntityAnalyzer.packageName(readBlob(apiBase, token, repoPath, existingCipher.sha()));
            securityDir = existingCipher.path().substring(0, existingCipher.path().lastIndexOf('/') + 1);
            appendLog(logText, "Reusing the repository's " + EncryptionCodeTemplates.CIPHER_CLASS + " in " + securityPkg);
        }

        // Map each approved column to its entity field; the entities themselves stay unchanged
        String moduleRoot = null;
        String persistence = "jakarta.persistence";
        String firstEntity = null;
        Map<String, Map<String, String>> encryptedFields = new LinkedHashMap<>();
        for (Map.Entry<String, List<ColumnPlan>> entry : byTable.entrySet()) {
            String table = entry.getKey();
            Map.Entry<String, String> entity = sources.entrySet().stream()
                .filter(e -> JpaEntityAnalyzer.mapsTable(e.getValue(), table))
                .findFirst().orElse(null);
            if (entity == null) {
                entry.getValue().forEach(p -> p.codeStatus = "NO_ENTITY");
                appendLog(logText, "No JPA entity found for table " + table + " (manual change needed)");
                continue;
            }
            String path = entity.getKey();
            String source = entity.getValue();
            if (moduleRoot == null) {
                moduleRoot = moduleRoot(path);
                firstEntity = source;
                if (source.contains("javax.persistence")) persistence = "javax.persistence";
            }
            JpaEntityAnalyzer.Result result = JpaEntityAnalyzer.analyze(source, entry.getValue().stream().map(p -> p.column).toList());
            for (ColumnPlan p : entry.getValue()) {
                p.codeStatus = result.columnOutcomes().getOrDefault(p.column, "NO_FIELD");
                p.entityFile = path;
            }
            result.fields().forEach((column, field) -> encryptedFields
                .computeIfAbsent(result.entityName(), k -> new LinkedHashMap<>())
                .put(field, column + JpaEntityAnalyzer.ENCRYPTED_SUFFIX));
            appendLog(logText, "Mapped " + path + " (unchanged): " + result.columnOutcomes());
        }
        if (encryptedFields.isEmpty()) {
            throw new IllegalStateException("No entity field could be mapped automatically. "
                + "Check that the GitHub connector's folders include the JPA entities for the approved tables.");
        }
        if (securityPkg == null) {
            String basePackage = parentPackage(JpaEntityAnalyzer.packageName(firstEntity));
            securityPkg = (basePackage.isEmpty() ? "" : basePackage + ".") + "security";
            securityDir = moduleRoot + "src/main/java/" + securityPkg.replace('.', '/') + "/";
        }

        String pkg = securityPkg;
        String dir = securityDir;
        addGenerated(dir + EncryptionCodeTemplates.CIPHER_CLASS + ".java", false, existing -> EncryptionCodeTemplates.cipher(pkg),
            apiBase, token, repoPath, files, changes, changeKinds, originals, logText);
        addGenerated(dir + JpaEntityAnalyzer.FLAGS_CLASS + ".java", false, existing -> EncryptionCodeTemplates.flags(pkg),
            apiBase, token, repoPath, files, changes, changeKinds, originals, logText);
        addGenerated(dir + EncryptionCodeTemplates.FIELDS_CLASS + ".java", true,
            existing -> EncryptionCodeTemplates.encryptedFields(pkg, encryptedFields, existing),
            apiBase, token, repoPath, files, changes, changeKinds, originals, logText);
        String jpa = persistence;
        addGenerated(dir + EncryptionCodeTemplates.LISTENER_CLASS + ".java", true, existing -> EncryptionCodeTemplates.listener(pkg, jpa),
            apiBase, token, repoPath, files, changes, changeKinds, originals, logText);

        rewriteNativeQueries(apiBase, token, repoPath, files, includes, byTable, pkg, dir,
            changes, changeKinds, originals, logText);

        addKeyConfig(apiBase, token, repoPath, files, moduleRoot, changes, changeKinds, originals, logText);
        addFlywayMigration(files, moduleRoot, plans, changes, changeKinds, logText);

        // The connector's folders mark the application being protected; never touch anything else in the repo
        requireWithinIncludes(changes.keySet(), includes);

        // One commit on a new branch created from the base branch
        List<Map<String, Object>> treeEntries = new ArrayList<>();
        changes.forEach((path, content) -> treeEntries.add(Map.of(
            "path", path, "mode", "100644", "type", "blob", "content", content)));
        String newTree = github.post(apiBase, token, repoPath + "/git/trees",
            Map.of("base_tree", baseTree, "tree", treeEntries)).path("sha").asText();
        String message = "PI Co-Pilot: add encrypted columns for approved PI\n\n"
            + plans.stream().filter(p -> p.codeStatus.startsWith(JpaEntityAnalyzer.MAPPED))
                .map(p -> "- " + p.table + "." + p.column + " -> " + p.encryptedColumn())
                .collect(Collectors.joining("\n"))
            + "\n\nExisting code is unchanged. The new classes only act while the encrypted-read flag is on;"
            + "\nwith it off the application behaves exactly as before."
            + "\nSet PI_LOCAL_ENCRYPTION_KEY to the workspace's LOCAL_DEV key before turning the flag on.";
        String commitSha = github.post(apiBase, token, repoPath + "/git/commits",
            Map.of("message", message, "tree", newTree, "parents", List.of(baseSha))).path("sha").asText();
        // Named after the application being protected; time plus run id keeps names unique
        String application = moduleRoot == null || moduleRoot.isEmpty() ? repo.getRepositoryName() : moduleRoot.split("/")[0];
        String branch = branchPrefix(application) + "/encrypt-pi-" + BRANCH_TIME.format(Instant.now())
            + "-" + run.getId().toString().substring(0, 6);
        github.post(apiBase, token, repoPath + "/git/refs", Map.of("ref", "refs/heads/" + branch, "sha", commitSha));
        appendLog(logText, "Pushed commit " + commitSha.substring(0, 7) + " to new branch " + branch);

        try {
            JsonNode pr = github.post(apiBase, token, repoPath + "/pulls", Map.of(
                "title", "PI Co-Pilot: encrypt personal data in " + application,
                "head", branch,
                "base", baseBranch,
                "body", pullRequestBody(application, plans)));
            run.setPrNumber(pr.path("number").asInt());
            run.setPrUrl(pr.path("html_url").asText(null));
            appendLog(logText, "Opened pull request #" + run.getPrNumber() + " for review");
        } catch (RuntimeException e) {
            // The branch is still usable; the review happens in PI Co-Pilot either way
            appendLog(logText, "Could not open a pull request (" + describe(e) + "); review the branch instead");
        }

        run.setRepository(repo.getRepositoryOwner() + "/" + repo.getRepositoryName());
        run.setBaseBranch(baseBranch);
        run.setBranchName(branch);
        run.setCommitSha(commitSha);
        run.setCompareUrl(webUrl(repo) + "/compare/" + encodePath(baseBranch) + "..." + encodePath(branch));
        run.setChangedFiles(changes.keySet().stream().map(p -> changeKinds.get(p) + "|" + p).collect(Collectors.joining("\n")));
        UUID runId = run.getId();
        changes.forEach((path, content) -> {
            ImplementationFileChange change = new ImplementationFileChange();
            change.setRunId(runId);
            change.setPath(path);
            change.setChangeKind(changeKinds.get(path));
            change.setBaseContent(originals.get(path));
            change.setPreviousContent(originals.get(path));
            change.setNewContent(content);
            fileChangeRepository.save(change);
        });
        return save(run, logText, "Code step completed (" + changes.size() + " file(s) changed)");
    }

    /**
     * Writes a generated class unless the repository already has it ({@code replace} = false), or replaces our own
     * earlier version ({@code replace} = true; the content function gets the current file to build on).
     */
    private void addGenerated(String path, boolean replace, java.util.function.Function<String, String> content,
                              String apiBase, String token, String repoPath, Map<String, RepoFile> files,
                              Map<String, String> changes, Map<String, String> kinds, Map<String, String> originals,
                              StringBuilder logText) {
        RepoFile current = files.get(path);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (current == null) {
            changes.put(path, content.apply(null));
            kinds.put(path, "ADDED");
            appendLog(logText, "Adding " + name);
        } else if (replace) {
            String original = readBlob(apiBase, token, repoPath, current.sha());
            String updated = content.apply(original);
            if (!updated.equals(original)) {
                originals.put(path, original);
                changes.put(path, updated);
                kinds.put(path, "MODIFIED");
                appendLog(logText, "Updating " + name);
            }
        } else {
            appendLog(logText, "Keeping the repository's " + name);
        }
    }

    /**
     * Native queries bypass the entity listener. Next to each one that selects approved columns a twin
     * {@code <name>PiEncrypted} method reading the encrypted columns is added (the original stays as it is), and
     * PiNativeQueryRouter routes to the twins while encrypted read is on. Each column's readStatus records the outcome.
     */
    private void rewriteNativeQueries(String apiBase, String token, String repoPath, Map<String, RepoFile> files,
                                      List<String> includes, Map<String, List<ColumnPlan>> byTable, String pkg, String dir,
                                      Map<String, String> changes, Map<String, String> kinds,
                                      Map<String, String> originals, StringBuilder logText) {
        List<RepoFile> repoFiles = files.values().stream()
            .filter(f -> f.path().endsWith(".java") && !f.path().contains("/test/"))
            .filter(f -> includes.isEmpty() || includes.stream().anyMatch(i -> f.path().startsWith(i.endsWith("/") ? i : i + "/")))
            .filter(f -> f.path().endsWith("Repository.java") || f.path().endsWith("Dao.java") || f.path().contains("/repository/"))
            .limit(MAX_FILE_FETCHES)
            .toList();
        Map<String, List<String>> columnsByTable = new LinkedHashMap<>();
        byTable.values().forEach(list -> {
            List<String> mapped = list.stream().filter(p -> p.codeStatus.startsWith(JpaEntityAnalyzer.MAPPED))
                .map(p -> p.column).toList();
            if (!mapped.isEmpty()) columnsByTable.put(list.get(0).table, mapped);
        });
        boolean routed = false;
        for (RepoFile f : repoFiles) {
            String original = readBlob(apiBase, token, repoPath, f.sha());
            if (!NativeQueryEncryptor.hasNativeQuery(original)) continue;
            NativeQueryEncryptor.MultiResult result = NativeQueryEncryptor.encrypt(original, columnsByTable);
            for (List<ColumnPlan> tablePlans : byTable.values()) {
                Map<String, String> outcomes = result.outcomes().getOrDefault(tablePlans.get(0).table, Map.of());
                for (ColumnPlan p : tablePlans) {
                    String outcome = outcomes.get(p.column);
                    // One unsupported query is enough to leave reads of the column partly plain
                    if (outcome != null && !NativeQueryEncryptor.UNSUPPORTED.equals(p.readStatus)) p.readStatus = outcome;
                }
            }
            if (!result.outcomes().isEmpty()) {
                appendLog(logText, "Native queries in " + f.path() + ": " + result.outcomes());
            }
            if (result.changed()) {
                originals.putIfAbsent(f.path(), original);
                changes.put(f.path(), result.source());
                kinds.putIfAbsent(f.path(), "MODIFIED");
                appendLog(logText, "Added " + NativeQueryEncryptor.METHOD_SUFFIX + " twin methods to " + f.path()
                    + " (existing methods unchanged)");
            }
            routed |= result.source().contains(NativeQueryEncryptor.METHOD_SUFFIX);
        }
        if (routed) {
            addGenerated(dir + EncryptionCodeTemplates.ROUTER_CLASS + ".java", true, existing -> EncryptionCodeTemplates.nativeQueryRouter(pkg),
                apiBase, token, repoPath, files, changes, kinds, originals, logText);
        }
        byTable.values().forEach(list -> list.forEach(p -> {
            if (NativeQueryEncryptor.UNSUPPORTED.equals(p.readStatus)) {
                appendLog(logText, "Warning: a native query reads " + p.table + "." + p.column
                    + " in a form that could not be rewritten; it keeps reading the plain column");
            }
        }));
    }

    /** "Employee Management" -> "employee-management": a valid git branch name segment. */
    static String branchPrefix(String application) {
        String slug = application.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^[-.]+|[-.]+$", "");
        return slug.isEmpty() ? "pi-copilot" : slug;
    }

    /** Fails if any path lies outside the connector's selected folders (no folders selected = whole repo). */
    static void requireWithinIncludes(java.util.Collection<String> paths, List<String> includes) {
        if (includes.isEmpty()) return;
        for (String path : paths) {
            boolean inside = includes.stream().anyMatch(i -> path.startsWith(i.endsWith("/") ? i : i + "/"));
            if (!inside) {
                throw new IllegalStateException("Refusing to change " + path
                    + ": it is outside the GitHub connector's selected folders " + includes);
            }
        }
    }

    private static String pullRequestBody(String application, List<ColumnPlan> plans) {
        StringBuilder body = new StringBuilder("Generated by **PI Co-Pilot** to encrypt personal data in **")
            .append(application).append("**.\n\n| Column | Encrypted column | Code | Encrypted read |\n|---|---|---|---|\n");
        for (ColumnPlan p : plans) {
            body.append("| `").append(p.table).append('.').append(p.column).append("` | `")
                .append(p.encryptedColumn()).append("` | ").append(p.codeStatus).append(" | ").append(p.readStatus).append(" |\n");
        }
        return body.append("\nThe encrypted columns already exist in the database. **Existing code is unchanged**: the new ")
            .append("classes (an entity listener and `PiEncrypted` twins of native queries) only act while the ")
            .append("`encrypted-read` flag is on, so deploying with the flag off changes nothing. ")
            .append("Review and approve this change in PI Co-Pilot's Final Review; approval merges it into the base branch.")
            .toString();
    }

    private void addKeyConfig(String apiBase, String token, String repoPath, Map<String, RepoFile> files, String moduleRoot,
                              Map<String, String> changes, Map<String, String> kinds, Map<String, String> originals,
                              StringBuilder logText) {
        // LOCAL_DEV keeps its key in configuration, so the application gets the same key as its default
        String localKey = environment.getProperty("picopilot.encryption.local-dev-key");
        String resources = moduleRoot + "src/main/resources/";
        RepoFile yml = files.get(resources + "application.yml");
        if (yml == null) yml = files.get(resources + "application.yaml");
        RepoFile props = files.get(resources + "application.properties");
        if (yml != null) {
            String original = readBlob(apiBase, token, repoPath, yml.sha());
            String updated = EncryptionCodeTemplates.withYamlKey(original, localKey);
            if (updated != null) {
                originals.put(yml.path(), original);
                changes.put(yml.path(), updated);
                kinds.put(yml.path(), "MODIFIED");
                appendLog(logText, "Added " + EncryptionCodeTemplates.KEY_PROPERTY + " to " + yml.path());
            } else {
                appendLog(logText, yml.path() + " already configures the key (or has a pi: block); left unchanged");
            }
        } else if (props != null) {
            String original = readBlob(apiBase, token, repoPath, props.sha());
            String updated = EncryptionCodeTemplates.withPropertiesKey(original, localKey);
            if (updated != null) {
                originals.put(props.path(), original);
                changes.put(props.path(), updated);
                kinds.put(props.path(), "MODIFIED");
                appendLog(logText, "Added " + EncryptionCodeTemplates.KEY_PROPERTY + " to " + props.path());
            }
        } else {
            appendLog(logText, "No application.yml/properties found; configure " + EncryptionCodeTemplates.KEY_PROPERTY + " manually");
        }
    }

    private void addFlywayMigration(Map<String, RepoFile> files, String moduleRoot, List<ColumnPlan> plans,
                                    Map<String, String> changes, Map<String, String> kinds, StringBuilder logText) {
        String dir = null;
        int max = 0;
        for (String path : files.keySet()) {
            Matcher m = FLYWAY_FILE.matcher(path);
            if (m.matches() && path.startsWith(moduleRoot)) {
                dir = m.group(1);
                max = Math.max(max, Integer.parseInt(m.group(2)));
            }
        }
        if (dir == null) {
            appendLog(logText, "No Flyway migrations in the repository; the columns exist only in the database");
            return;
        }
        String path = dir + "V" + (max + 1) + "__pi_copilot_encrypted_columns.sql";
        Set<String> ddl = new java.util.LinkedHashSet<>();
        ddl.add(EncryptionCodeTemplates.FLAG_TABLE_DDL);
        plans.stream().map(ColumnPlan::ddl).forEach(ddl::add);
        changes.put(path, EncryptionCodeTemplates.migration(List.copyOf(ddl)));
        kinds.put(path, "ADDED");
        appendLog(logText, "Added Flyway migration " + path);
    }

    private String readBlob(String apiBase, String token, String repoPath, String sha) {
        String content = github.get(apiBase, token, repoPath + "/git/blobs/" + sha).path("content").asText("");
        return new String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8);
    }

    private ImplementationRun save(ImplementationRun run, StringBuilder logText, String line) {
        if (line != null) appendLog(logText, line);
        run.setLog(logText.toString());
        // save() returns the entity with its new @Version; always continue with the returned instance
        return runRepository.save(run);
    }

    private static void appendLog(StringBuilder logText, String line) {
        logText.append(Instant.now()).append(' ').append(line).append('\n');
    }

    private String columnsJson(List<ColumnPlan> plans) {
        try {
            return objectMapper.writeValueAsString(plans.stream().map(ColumnPlan::toJson).toList());
        } catch (Exception e) {
            return "[]";
        }
    }

    /** "employees" matches Employee.java / Employees.java / EmployeeEntity.java. */
    private static boolean nameMatches(String path, String table) {
        String file = path.substring(path.lastIndexOf('/') + 1).replace(".java", "").toLowerCase(Locale.ROOT);
        String t = table.replace("_", "");
        String singular = t.endsWith("ies") ? t.substring(0, t.length() - 3) + "y"
            : t.endsWith("s") ? t.substring(0, t.length() - 1) : t;
        return file.equals(t) || file.equals(singular) || file.equals(singular + "entity");
    }

    private static String moduleRoot(String path) {
        int i = path.indexOf("src/main/java/");
        return i < 0 ? "" : path.substring(0, i);
    }

    private static String parentPackage(String pkg) {
        int i = pkg.lastIndexOf('.');
        return i < 0 ? pkg : pkg.substring(0, i);
    }

    private static String webUrl(RepositoryConnector repo) {
        String url = repo.getRepositoryUrl().replaceAll("\\.git$", "").replaceAll("/+$", "");
        return url;
    }

    private static String encodePath(String branch) {
        return java.util.Arrays.stream(branch.split("/")).map(GitHubApiClient::encode).collect(Collectors.joining("/"));
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
