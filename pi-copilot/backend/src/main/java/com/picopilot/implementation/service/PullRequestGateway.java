package com.picopilot.implementation.service;

import com.picopilot.common.crypto.SecretCipher;
import com.picopilot.project.github.GitHubApiClient;
import com.picopilot.project.model.RepositoryConnector;
import com.picopilot.project.repository.RepositoryConnectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.picopilot.project.github.GitHubApiClient.encode;

/** GitHub operations on an implementation's branch and pull request. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PullRequestGateway {

    private final RepositoryConnectorRepository repoConnectorRepository;
    private final SecretCipher secretCipher;
    private final GitHubApiClient github;

    /** Credentials and paths for one repository connector. */
    public record Repo(String apiBase, String token, String repoPath, List<String> includes, String owner, String name) {}

    /** Identifies a review thread by the path and text of its first comment. */
    public record ThreadRef(String path, String body) {}

    public Repo repo(UUID repositoryConnectorId) {
        RepositoryConnector connector = repoConnectorRepository.findById(repositoryConnectorId)
            .orElseThrow(() -> new IllegalStateException("The GitHub connector no longer exists"));
        String apiBase = connector.getApiBaseUrl() != null ? connector.getApiBaseUrl()
            : github.resolveApiBase(connector.getRepositoryUrl());
        List<String> includes = connector.getIncludePaths() == null ? List.of()
            : connector.getIncludePaths().lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new Repo(apiBase, secretCipher.decrypt(connector.getEncryptedToken()),
            "/repos/" + encode(connector.getRepositoryOwner()) + "/" + encode(connector.getRepositoryName()), includes,
            connector.getRepositoryOwner(), connector.getRepositoryName());
    }

    /**
     * Commits the given files on top of the branch head and moves the branch to the new commit.
     *
     * @return the new commit sha
     */
    public String commitToBranch(Repo repo, String branch, Map<String, String> files, String message) {
        String refPath = repo.repoPath() + "/git/refs/heads/" + encodePath(branch);
        String head = github.get(repo.apiBase(), repo.token(), repo.repoPath() + "/git/ref/heads/" + encodePath(branch))
            .path("object").path("sha").asText();
        String baseTree = github.get(repo.apiBase(), repo.token(), repo.repoPath() + "/git/commits/" + head)
            .path("tree").path("sha").asText();
        List<Map<String, Object>> entries = new ArrayList<>();
        files.forEach((path, content) -> entries.add(Map.of("path", path, "mode", "100644", "type", "blob", "content", content)));
        String tree = github.post(repo.apiBase(), repo.token(), repo.repoPath() + "/git/trees",
            Map.of("base_tree", baseTree, "tree", entries)).path("sha").asText();
        String commit = github.post(repo.apiBase(), repo.token(), repo.repoPath() + "/git/commits",
            Map.of("message", message, "tree", tree, "parents", List.of(head))).path("sha").asText();
        github.patch(repo.apiBase(), repo.token(), refPath, Map.of("sha", commit, "force", false));
        return commit;
    }

    /** One inline comment for a GitHub pull request review. */
    public record InlineComment(String path, int line, boolean originalSide, boolean wholeFile, String body) {}

    /**
     * Best effort: posts a pull request review with inline comments. GitHub refuses APPROVE and
     * REQUEST_CHANGES on the token owner's own pull request, so it falls back to a COMMENT review,
     * and to a plain comment listing the line comments if GitHub rejects the line positions.
     *
     * @param event APPROVE, REQUEST_CHANGES or COMMENT
     */
    public void review(Repo repo, Integer prNumber, String commitSha, String event, String body,
                       List<InlineComment> comments) {
        if (prNumber == null) return;
        List<Map<String, Object>> inline = comments.stream()
            .map(c -> c.wholeFile()
                ? Map.<String, Object>of("path", c.path(), "subject_type", "file", "body", c.body())
                : Map.<String, Object>of("path", c.path(), "line", c.line(),
                    "side", c.originalSide() ? "LEFT" : "RIGHT", "body", c.body()))
            .toList();
        String reviewPath = repo.repoPath() + "/pulls/" + prNumber + "/reviews";
        for (String attempt : event.equals("COMMENT") ? List.of("COMMENT") : List.of(event, "COMMENT")) {
            try {
                Map<String, Object> request = new java.util.HashMap<>();
                request.put("event", attempt);
                request.put("body", body);
                if (commitSha != null) request.put("commit_id", commitSha);
                if (!inline.isEmpty()) request.put("comments", inline);
                github.post(repo.apiBase(), repo.token(), reviewPath, request);
                return;
            } catch (RuntimeException e) {
                log.info("GitHub refused a {} review on #{}: {}", attempt, prNumber, e.getMessage());
            }
        }
        StringBuilder text = new StringBuilder(body);
        for (InlineComment c : comments) {
            text.append("\n\n**").append(c.path()).append(c.wholeFile() ? "" : " line " + c.line())
                .append("**\n").append(c.body());
        }
        comment(repo, prNumber, text.toString());
    }

    /** Best effort: a comment that cannot be posted never blocks the review. */
    public void comment(Repo repo, Integer prNumber, String text) {
        if (prNumber == null) return;
        try {
            github.post(repo.apiBase(), repo.token(), repo.repoPath() + "/issues/" + prNumber + "/comments", Map.of("body", text));
        } catch (RuntimeException e) {
            log.info("Could not comment on pull request #{}: {}", prNumber, e.getMessage());
        }
    }

    /**
     * Best effort: marks the pull request review threads of resolved comments as resolved. GitHub only
     * offers this through GraphQL; threads are matched by path and the text of their first comment.
     */
    public void resolveThreads(Repo repo, Integer prNumber, List<ThreadRef> resolved) {
        if (prNumber == null || resolved.isEmpty()) return;
        // https://api.github.com -> /graphql; GitHub Enterprise https://host/api/v3 -> https://host/api/graphql
        String graphqlBase = repo.apiBase().endsWith("/api/v3")
            ? repo.apiBase().substring(0, repo.apiBase().length() - 3) : repo.apiBase();
        if (graphqlBase.endsWith("/")) graphqlBase = graphqlBase.substring(0, graphqlBase.length() - 1);
        try {
            JsonNode threads = github.post(graphqlBase, repo.token(), "/graphql", Map.of(
                "query", "query($owner:String!,$name:String!,$number:Int!){repository(owner:$owner,name:$name){"
                    + "pullRequest(number:$number){reviewThreads(first:100){nodes{id isResolved "
                    + "comments(first:1){nodes{path body}}}}}}}",
                "variables", Map.of("owner", repo.owner(), "name", repo.name(), "number", prNumber)))
                .path("data").path("repository").path("pullRequest").path("reviewThreads").path("nodes");
            for (JsonNode thread : threads) {
                if (thread.path("isResolved").asBoolean()) continue;
                JsonNode first = thread.path("comments").path("nodes").path(0);
                boolean matches = resolved.stream().anyMatch(r -> r.path().equals(first.path("path").asText())
                    && first.path("body").asText().trim().equals(r.body().trim()));
                if (matches) {
                    github.post(graphqlBase, repo.token(), "/graphql", Map.of(
                        "query", "mutation($id:ID!){resolveReviewThread(input:{threadId:$id}){thread{id isResolved}}}",
                        "variables", Map.of("id", thread.path("id").asText())));
                }
            }
        } catch (RuntimeException e) {
            log.info("Could not resolve review threads on #{}: {}", prNumber, e.getMessage());
        }
    }

    /**
     * Merges an approved implementation into the base branch: through its pull request when it has one,
     * otherwise by merging the branch directly. A pull request merged earlier counts as merged.
     *
     * @return the merge commit sha
     */
    public String merge(Repo repo, Integer prNumber, String branch, String baseBranch, String title) {
        return merge(repo, prNumber, branch, baseBranch, title, null);
    }

    /**
     * Like {@link #merge(Repo, Integer, String, String, String)}, but guarantees that {@code expectedHead} (a commit just
     * pushed to the branch) ends up in the base branch. GitHub updates a pull request's head asynchronously after a push,
     * so merging right away can merge the previous head; this waits for the pull request to show the commit, pins the merge
     * to it, and checks the base branch afterwards. A pull request merged earlier without the commit is followed by a
     * direct merge of the branch.
     *
     * @return the merge commit sha
     */
    public String merge(Repo repo, Integer prNumber, String branch, String baseBranch, String title, String expectedHead) {
        if (expectedHead == null) return mergeOnce(repo, prNumber, branch, baseBranch, title, null);
        String sha;
        if (prNumber != null && !awaitPullRequestHead(repo, prNumber, expectedHead)) {
            // Merged earlier (without the commit) or GitHub never caught up: merge the branch itself
            sha = mergeOnce(repo, null, branch, baseBranch, title, null);
        } else {
            sha = mergeOnce(repo, prNumber, branch, baseBranch, title, expectedHead);
        }
        if (!containsCommit(repo, baseBranch, expectedHead)) {
            throw new IllegalStateException(baseBranch + " does not contain commit " + expectedHead.substring(0, 7)
                + " after the merge; merge " + branch + " into " + baseBranch + " manually");
        }
        return sha;
    }

    /** True once the open pull request's head is {@code expectedHead}; false if it is merged/closed or never updates. */
    private boolean awaitPullRequestHead(Repo repo, int prNumber, String expectedHead) {
        String pr = repo.repoPath() + "/pulls/" + prNumber;
        for (int attempt = 0; attempt < 15; attempt++) {
            JsonNode current = github.get(repo.apiBase(), repo.token(), pr);
            if (current.path("merged").asBoolean() || !"open".equals(current.path("state").asText())) return false;
            if (expectedHead.equals(current.path("head").path("sha").asText())) return true;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** True if {@code commit} is reachable from {@code branch}. */
    public boolean containsCommit(Repo repo, String branch, String commit) {
        JsonNode compare = github.get(repo.apiBase(), repo.token(),
            repo.repoPath() + "/compare/" + commit + "..." + encodePath(branch));
        String status = compare.path("status").asText();
        return "identical".equals(status) || "ahead".equals(status);
    }

    private String mergeOnce(Repo repo, Integer prNumber, String branch, String baseBranch, String title, String headSha) {
        if (prNumber != null) {
            String pr = repo.repoPath() + "/pulls/" + prNumber;
            JsonNode current = github.get(repo.apiBase(), repo.token(), pr);
            if (current.path("merged").asBoolean()) return current.path("merge_commit_sha").asText(null);
            Map<String, Object> request = new java.util.HashMap<>();
            request.put("merge_method", "merge");
            request.put("commit_title", title);
            // GitHub refuses the merge if the head is not this commit, instead of merging an older head
            if (headSha != null) request.put("sha", headSha);
            return github.put(repo.apiBase(), repo.token(), pr + "/merge", request).path("sha").asText(null);
        }
        JsonNode merged = github.post(repo.apiBase(), repo.token(), repo.repoPath() + "/merges",
            Map.of("base", baseBranch, "head", branch, "commit_message", title));
        // 204 No Content: the base branch already contains the branch
        return merged == null || merged.isMissingNode() ? null : merged.path("sha").asText(null);
    }

    /** Content of a file on a branch, or null if it does not exist there. */
    public String readFile(Repo repo, String branch, String path) {
        JsonNode file;
        try {
            file = github.get(repo.apiBase(), repo.token(),
                repo.repoPath() + "/contents/" + encodePath(path) + "?ref=" + encode(branch));
        } catch (RuntimeException e) {
            log.info("Could not read {} on {}: {}", path, branch, e.getMessage());
            return null;
        }
        String content = file.path("content").asText(null);
        if (content == null || !"base64".equals(file.path("encoding").asText())) return null;
        return new String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8);
    }

    /** Best effort: closes the pull request of a rejected implementation. */
    public void close(Repo repo, Integer prNumber) {
        if (prNumber == null) return;
        try {
            github.patch(repo.apiBase(), repo.token(), repo.repoPath() + "/pulls/" + prNumber, Map.of("state", "closed"));
        } catch (RuntimeException e) {
            log.info("Could not close pull request #{}: {}", prNumber, e.getMessage());
        }
    }

    static String encodePath(String branch) {
        return Arrays.stream(branch.split("/")).map(GitHubApiClient::encode).collect(Collectors.joining("/"));
    }
}
