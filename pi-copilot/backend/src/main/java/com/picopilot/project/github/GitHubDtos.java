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

    public record SaveConnectorRequest(@NotBlank String githubUrl, @NotBlank String token,
                                       @NotBlank String owner, @NotBlank String repo,
                                       @NotBlank String branch) {}

    public record RepositorySummary(String owner, String name, String fullName, String description,
                                    boolean privateRepo, String defaultBranch, String htmlUrl,
                                    Instant updatedAt) {}

    public record ConnectResponse(String login, String apiBaseUrl, List<RepositorySummary> repositories) {}

    public record BranchSummary(String name, boolean protectedBranch) {}

    public record ConnectorSummary(UUID id, UUID projectId, String projectName, String repositoryUrl,
                                   String owner, String name, String branch, String apiBaseUrl,
                                   boolean verified, String status, Instant createdAt) {}
}
