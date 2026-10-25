package com.picopilot.project.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.picopilot.common.crypto.SecretCipher;
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

import com.picopilot.common.exception.ValidationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
        List<String> includePaths = validateIncludePaths(apiBase, request);

        ApplicationProject project = projectRepository.findByWorkspaceId(workspaceId).stream()
            .filter(p -> p.getName().equalsIgnoreCase(repo.fullName()))
            .findFirst()
            .orElseGet(() -> {
                ApplicationProject p = new ApplicationProject();
                p.setWorkspaceId(workspaceId);
                p.setName(repo.fullName());
                p.setDescription(truncate(repo.description(), 500));
                return p;
            });
        project.setDefaultBranch(request.branch());
        project = projectRepository.save(project);

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
        connector.setIncludePaths(includePaths.isEmpty() ? null : String.join("\n", includePaths));
        connector.setEncryptedToken(secretCipher.encrypt(request.token()));
        connector.setVerified(true);
        connector.setStatus("ACTIVE");
        connector = connectorRepository.save(connector);

        log.info("User {} saved GitHub connector {} for {}@{}", userId, connector.getId(),
            repo.fullName(), request.branch());
        return toSummary(connector);
    }

    @Transactional(readOnly = true)
    public List<ConnectorSummary> list(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return connectorRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
            .map(this::toSummary)
            .toList();
    }

    /** Every selected folder must exist on the branch; nested selections collapse into their parent. */
    private List<String> validateIncludePaths(String apiBase, SaveConnectorRequest request) {
        List<String> requested = request.includePaths() == null ? List.of() : request.includePaths().stream()
            .map(p -> p.trim().replaceAll("^/+|/+$", ""))
            .filter(p -> !p.isEmpty())
            .distinct()
            .sorted()
            .toList();
        if (requested.size() > MAX_INCLUDE_PATHS) {
            throw new ValidationException("Select at most " + MAX_INCLUDE_PATHS + " folders");
        }
        // Also verifies the branch is accessible
        FoldersResponse folders = fetchFolders(apiBase, request.token(), request.owner(), request.repo(), request.branch());
        if (requested.isEmpty()) {
            return requested;
        }
        Set<String> known = new HashSet<>(folders.folders());
        List<String> result = new ArrayList<>();
        for (String path : requested) {
            if (path.contains("..") || (!known.contains(path) && !folders.truncated())) {
                throw new ValidationException("Folder not found on branch " + request.branch() + ": " + path);
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

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
