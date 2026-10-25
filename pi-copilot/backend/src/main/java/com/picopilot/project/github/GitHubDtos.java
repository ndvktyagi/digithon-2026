package com.picopilot.project.github;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class GitHubDtos {

    private GitHubDtos() {}

    public record ConnectRequest(@NotBlank String githubUrl, @NotBlank String token) {}

    public record BranchesRequest(@NotBlank String githubUrl, @NotBlank String token,
                                  @NotBlank String owner, @NotBlank String repo) {}

    /** projectName is optional; it defaults to the repository's full name. */
    public record SaveConnectorRequest(@NotBlank String githubUrl, @NotBlank String token,
                                       @NotBlank String owner, @NotBlank String repo,
                                       @NotBlank String branch, List<String> includePaths,
                                       String projectName) {}

    /** Repository stays fixed; a blank token keeps the stored one. */
    public record UpdateConnectorRequest(String projectName, String token,
                                         @NotBlank String branch, List<String> includePaths) {}

    public record FoldersRequest(@NotBlank String githubUrl, @NotBlank String token,
                                 @NotBlank String owner, @NotBlank String repo,
                                 @NotBlank String branch) {}

    /** Directory paths of the branch; truncated when GitHub's tree listing hit its size limit. */
    public record FoldersResponse(List<String> folders, boolean truncated) {}

    public record RepositorySummary(String owner, String name, String fullName, String description,
                                    boolean privateRepo, String defaultBranch, String htmlUrl,
                                    Instant updatedAt) {}

    public record ConnectResponse(String login, String apiBaseUrl, List<RepositorySummary> repositories) {}

    public record BranchSummary(String name, boolean protectedBranch) {}

    public record ConnectorSummary(UUID id, UUID projectId, String projectName, String repositoryUrl,
                                   String owner, String name, String branch, String apiBaseUrl,
                                   List<String> includePaths, boolean verified, String status, Instant createdAt) {}
}
