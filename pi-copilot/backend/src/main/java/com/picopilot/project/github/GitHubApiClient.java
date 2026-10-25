package com.picopilot.project.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picopilot.common.exception.ConnectorException;
import com.picopilot.common.exception.ValidationException;
import com.picopilot.common.net.HostPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal GitHub REST client for github.com and GitHub Enterprise Server.
 * Tokens are only sent to the API host derived from the user-supplied URL.
 */
@Component
@RequiredArgsConstructor
public class GitHubApiClient {

    private static final int PER_PAGE = 100;
    private static final int MAX_PAGES = 10;

    private final ObjectMapper objectMapper;
    private final HostPolicy hostPolicy;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    /**
     * Maps a GitHub web URL to its REST API base:
     * https://github.com -> https://api.github.com, https://ghe.corp.com -> https://ghe.corp.com/api/v3.
     */
    public String resolveApiBase(String githubUrl) {
        URI uri;
        try {
            uri = URI.create(githubUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid GitHub URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new ValidationException("GitHub URL must be an https:// URL");
        }
        String host = uri.getHost().toLowerCase();
        hostPolicy.requireAllowed(host);
        String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        if (host.equals("github.com") || host.equals("www.github.com") || host.equals("api.github.com")) {
            return "https://api.github.com";
        }
        // Any repo path in the URL (e.g. /org/repo.git) is ignored; Enterprise API lives at /api/v3
        return "https://" + host + port + "/api/v3";
    }

    public JsonNode get(String apiBase, String token, String path) {
        return send("GET", apiBase, token, path, null);
    }

    public JsonNode post(String apiBase, String token, String path, Object body) {
        return send("POST", apiBase, token, path, body);
    }

    public JsonNode patch(String apiBase, String token, String path, Object body) {
        return send("PATCH", apiBase, token, path, body);
    }

    public JsonNode put(String apiBase, String token, String path, Object body) {
        return send("PUT", apiBase, token, path, body);
    }

    private JsonNode send(String method, String apiBase, String token, String path, Object body) {
        HttpRequest.BodyPublisher publisher;
        try {
            publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body));
        } catch (IOException e) {
            throw new ConnectorException("Could not serialize GitHub request", e);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + token)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "pi-copilot")
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ConnectorException("Could not reach GitHub at " + apiBase, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("GitHub request interrupted", e);
        }
        int status = response.statusCode();
        boolean write = !"GET".equals(method);
        if (status == 401) {
            throw new ValidationException("GitHub rejected the access token (invalid or expired)");
        }
        if (status == 403 || status == 404) {
            throw new ValidationException(write
                ? "The access token cannot write to this repository (it needs Contents: read and write)"
                : "The access token cannot access this resource on GitHub");
        }
        if (status == 422) {
            throw new ValidationException("GitHub rejected the request: " + githubMessage(response.body()));
        }
        if (status >= 300) {
            throw new ConnectorException("GitHub returned HTTP " + status + ": " + githubMessage(response.body()), null);
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new ConnectorException("Unexpected response from GitHub", e);
        }
    }

    private String githubMessage(String body) {
        try {
            return objectMapper.readTree(body).path("message").asText("unknown error");
        } catch (IOException e) {
            return "unknown error";
        }
    }

    /** Follows page numbers until a short page is returned or MAX_PAGES is reached. */
    public List<JsonNode> getAll(String apiBase, String token, String path) {
        List<JsonNode> items = new ArrayList<>();
        String sep = path.contains("?") ? "&" : "?";
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode batch = get(apiBase, token, path + sep + "per_page=" + PER_PAGE + "&page=" + page);
            batch.forEach(items::add);
            if (batch.size() < PER_PAGE) {
                break;
            }
        }
        return items;
    }

    public static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
