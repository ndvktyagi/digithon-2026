package com.picopilot.project.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.picopilot.audit.service.AuditService;
import com.picopilot.common.crypto.SecretCipher;
import com.picopilot.common.model.Severity;
import com.picopilot.identity.service.WorkspaceService;
import com.picopilot.project.github.GitHubDtos.*;
import com.picopilot.project.model.ApplicationProject;
import com.picopilot.project.model.RepositoryConnector;
import com.picopilot.project.repository.ApplicationProjectRepository;
import com.picopilot.project.repository.RepositoryConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.picopilot.common.exception.ResourceNotFoundException;
import com.picopilot.common.exception.ValidationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.picopilot.project.github.GitHubApiClient.encode;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubConnectorService {

    private static final String CONNECTOR_TYPE = "GITHUB_TOKEN";
    private static final int MAX_FOLDERS = 5000;
    private static final int MAX_INCLUDE_PATHS = 200;

    private final GitHubApiClient github;
    private final SecretCipher secretCipher;
    private final WorkspaceService workspaceService;
    private final ApplicationProjectRepository projectRepository;
    private final RepositoryConnectorRepository connectorRepository;
    private final AuditService auditService;

    /** Validates the token and lists every repository it can access. */
    public ConnectResponse connect(UUID workspaceId, ConnectRequest request, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        String apiBase = github.resolveApiBase(request.githubUrl());
        String login = github.get(apiBase, request.token(), "/user").path("login").asText();
        List<RepositorySummary> repos = github
            .getAll(apiBase, request.token(),
                "/user/repos?sort=updated&affiliation=owner,collaborator,organization_member")
            .stream()
            .map(this::toRepository)
            .sorted(Comparator.comparing(RepositorySummary::fullName, String.CASE_INSENSITIVE_ORDER))
            .toList();
        log.info("User {} connected GitHub account {} ({} repositories)", userId, login, repos.size());
        return new ConnectResponse(login, apiBase, repos);
    }

    public List<BranchSummary> listBranches(UUID workspaceId, BranchesRequest request, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        String apiBase = github.resolveApiBase(request.githubUrl());
        return github
            .getAll(apiBase, request.token(),
                "/repos/" + encode(request.owner()) + "/" + encode(request.repo()) + "/branches")
            .stream()
            .map(b -> new BranchSummary(b.path("name").asText(), b.path("protected").asBoolean()))
            .toList();
    }

    public FoldersResponse listFolders(UUID workspaceId, FoldersRequest request, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        String apiBase = github.resolveApiBase(request.githubUrl());
        return fetchFolders(apiBase, request.token(), request.owner(), request.repo(), request.branch());
    }

    /** Resolves the branch to its tree SHA (safe for branch names containing '/') and lists directories. */
    private FoldersResponse fetchFolders(String apiBase, String token, String owner, String repo, String branch) {
        String repoPath = "/repos/" + encode(owner) + "/" + encode(repo);
        String treeSha = github.get(apiBase, token, repoPath + "/branches/" + encode(branch))
            .path("commit").path("commit").path("tree").path("sha").asText();
        JsonNode tree = github.get(apiBase, token, repoPath + "/git/trees/" + encode(treeSha) + "?recursive=1");
        List<String> folders = new ArrayList<>();
        for (JsonNode entry : tree.path("tree")) {
            if ("tree".equals(entry.path("type").asText()) && folders.size() < MAX_FOLDERS) {
                folders.add(entry.path("path").asText());
            }
        }
        folders.sort(String.CASE_INSENSITIVE_ORDER);
        boolean truncated = tree.path("truncated").asBoolean() || folders.size() >= MAX_FOLDERS;
        return new FoldersResponse(folders, truncated);
    }

    @Transactional
    public ConnectorSummary save(UUID workspaceId, SaveConnectorRequest request, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        String apiBase = github.resolveApiBase(request.githubUrl());
        String repoPath = "/repos/" + encode(request.owner()) + "/" + encode(request.repo());

        // Re-verify access server-side; never trust the client's repo/branch selection.
        RepositorySummary repo = toRepository(github.get(apiBase, request.token(), repoPath));
        List<String> includePaths = validateIncludePaths(apiBase, request.token(), request.owner(),
            request.repo(), request.branch(), request.includePaths());

        ApplicationProject project = resolveProject(workspaceId,
            isBlank(request.projectName()) ? repo.fullName() : request.projectName().trim(),
            repo.description(), request.branch());

        RepositoryConnector connector = connectorRepository
            .findByProjectIdAndRepositoryName(project.getId(), repo.name())
            .orElseGet(RepositoryConnector::new);
        connector.setWorkspaceId(workspaceId);
        connector.setProject(project);
        connector.setConnectorType(CONNECTOR_TYPE);
        connector.setRepositoryUrl(repo.htmlUrl());
        connector.setRepositoryOwner(repo.owner());
        connector.setRepositoryName(repo.name());
        connector.setApiBaseUrl(apiBase);
        connector.setBranch(request.branch());
        connector.setIncludePaths(joinPaths(includePaths));
        connector.setEncryptedToken(secretCipher.encrypt(request.token()));
        connector.setVerified(true);
        connector.setStatus("ACTIVE");
        connector = connectorRepository.save(connector);

        log.info("User {} saved GitHub connector {} for {}@{}", userId, connector.getId(),
            repo.fullName(), request.branch());
        audit(connector, "GITHUB_CONNECTOR_SAVED", userId);
        return toSummary(connector);
    }

    @Transactional
    public ConnectorSummary update(UUID workspaceId, UUID connectorId, UpdateConnectorRequest request, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        RepositoryConnector connector = find(workspaceId, connectorId);
        String token = isBlank(request.token()) ? storedToken(connector) : request.token();
        String apiBase = connector.getApiBaseUrl();
        String owner = connector.getRepositoryOwner();
        String name = connector.getRepositoryName();

        // Verifies the token still reaches the repository and the branch/folders exist.
        github.get(apiBase, token, "/repos/" + encode(owner) + "/" + encode(name));
        List<String> includePaths = validateIncludePaths(apiBase, token, owner, name,
            request.branch(), request.includePaths());

        String projectName = isBlank(request.projectName())
            ? connector.getProject().getName() : request.projectName().trim();
        connector.setProject(resolveProject(workspaceId, projectName, null, request.branch()));
        connector.setBranch(request.branch());
        connector.setIncludePaths(joinPaths(includePaths));
        connector.setEncryptedToken(secretCipher.encrypt(token));
        connector.setVerified(true);
        connector = connectorRepository.save(connector);

        log.info("User {} updated GitHub connector {}", userId, connectorId);
        audit(connector, "GITHUB_CONNECTOR_UPDATED", userId);
        return toSummary(connector);
    }

    @Transactional(readOnly = true)
    public ConnectorSummary get(UUID workspaceId, UUID connectorId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return toSummary(find(workspaceId, connectorId));
    }

    @Transactional(readOnly = true)
    public List<BranchSummary> listBranches(UUID workspaceId, UUID connectorId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        RepositoryConnector c = find(workspaceId, connectorId);
        return listBranches(workspaceId, new BranchesRequest(c.getApiBaseUrl(), storedToken(c),
            c.getRepositoryOwner(), c.getRepositoryName()), userId);
    }

    @Transactional(readOnly = true)
    public FoldersResponse listFolders(UUID workspaceId, UUID connectorId, String branch, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        RepositoryConnector c = find(workspaceId, connectorId);
        return fetchFolders(c.getApiBaseUrl(), storedToken(c), c.getRepositoryOwner(), c.getRepositoryName(), branch);
    }

    @Transactional
    public void delete(UUID workspaceId, UUID connectorId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        RepositoryConnector connector = find(workspaceId, connectorId);
        connectorRepository.delete(connector);
        log.info("User {} deleted GitHub connector {}", userId, connectorId);
        audit(connector, "GITHUB_CONNECTOR_DELETED", userId);
    }

    private void audit(RepositoryConnector connector, String action, String userId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("branch", connector.getBranch());
        auditService.record(connector.getWorkspaceId(), connector.getProject() == null ? null : connector.getProject().getId(),
            userId, null, action, "GITHUB_CONNECTOR", connector.getId().toString(),
            connector.getRepositoryOwner() + "/" + connector.getRepositoryName(), "SUCCESS",
            action.endsWith("_DELETED") ? Severity.MEDIUM : Severity.INFO, null, null, metadata);
    }

    @Transactional(readOnly = true)
    public List<ConnectorSummary> list(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return connectorRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
            .map(this::toSummary)
            .toList();
    }

    private RepositoryConnector find(UUID workspaceId, UUID connectorId) {
        return connectorRepository.findById(connectorId)
            .filter(c -> workspaceId.equals(c.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("RepositoryConnector", connectorId.toString()));
    }

    private String storedToken(RepositoryConnector connector) {
        if (isBlank(connector.getEncryptedToken())) {
            throw new ValidationException("No access token is stored for this connector; enter a new token");
        }
        return secretCipher.decrypt(connector.getEncryptedToken());
    }

    private ApplicationProject resolveProject(UUID workspaceId, String name, String description, String branch) {
        ApplicationProject project = projectRepository.findByWorkspaceId(workspaceId).stream()
            .filter(p -> p.getName().equalsIgnoreCase(name))
            .findFirst()
            .orElseGet(() -> {
                ApplicationProject p = new ApplicationProject();
                p.setWorkspaceId(workspaceId);
                p.setName(name);
                p.setDescription(truncate(description, 500));
                return p;
            });
        project.setDefaultBranch(branch);
        return projectRepository.save(project);
    }

    /** Every selected folder must exist on the branch; nested selections collapse into their parent. */
    private List<String> validateIncludePaths(String apiBase, String token, String owner, String repo,
                                              String branch, List<String> paths) {
        List<String> requested = paths == null ? List.of() : paths.stream()
            .map(p -> p.trim().replaceAll("^/+|/+$", ""))
            .filter(p -> !p.isEmpty())
            .distinct()
            .sorted()
            .toList();
        if (requested.size() > MAX_INCLUDE_PATHS) {
            throw new ValidationException("Select at most " + MAX_INCLUDE_PATHS + " folders");
        }
        // Also verifies the branch is accessible
        FoldersResponse folders = fetchFolders(apiBase, token, owner, repo, branch);
        if (requested.isEmpty()) {
            return requested;
        }
        Set<String> known = new HashSet<>(folders.folders());
        List<String> result = new ArrayList<>();
        for (String path : requested) {
            if (path.contains("..") || (!known.contains(path) && !folders.truncated())) {
                throw new ValidationException("Folder not found on branch " + branch + ": " + path);
            }
            if (result.stream().noneMatch(parent -> path.startsWith(parent + "/"))) {
                result.add(path);
            }
        }
        return result;
    }

    private RepositorySummary toRepository(JsonNode r) {
        String updated = r.path("updated_at").asText(null);
        return new RepositorySummary(
            r.path("owner").path("login").asText(),
            r.path("name").asText(),
            r.path("full_name").asText(),
            r.path("description").isNull() ? null : r.path("description").asText(null),
            r.path("private").asBoolean(),
            r.path("default_branch").asText("main"),
            r.path("html_url").asText(),
            updated == null ? null : Instant.parse(updated));
    }

    private ConnectorSummary toSummary(RepositoryConnector c) {
        return new ConnectorSummary(c.getId(), c.getProject().getId(), c.getProject().getName(),
            c.getRepositoryUrl(), c.getRepositoryOwner(), c.getRepositoryName(), c.getBranch(),
            c.getApiBaseUrl(),
            c.getIncludePaths() == null || c.getIncludePaths().isBlank() ? List.of() : List.of(c.getIncludePaths().split("\n")),
            c.isVerified(), c.getStatus(), c.getCreatedAt());
    }

    private static String joinPaths(List<String> paths) {
        return paths.isEmpty() ? null : String.join("\n", paths);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
