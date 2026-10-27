package com.picopilot.implementation.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Applies a reviewer's Final Review comment to the files of an implementation pull request
 * using Claude, and returns the full content of every file it changes.
 */
@Slf4j
@Component
public class CodeReviser {

    private static final String SYSTEM_PROMPT = """
        You revise a pull request that adds field-level encryption of personal data to a Java application.
        The pull request is a release branch: once deployed, every save writes both the plain column and its \
        encrypted copy ("<column>_enc"), and reads come from the encrypted copy. The base branch is the \
        rollback, so the plain columns must stay mapped and written. The pull request adds an AES-256-GCM \
        cipher (PiFieldCipher, ciphertext format "enc:v1:"), a converter (PiEncryptedStringConverter), next \
        to each plain entity field a copy @Column(name = "<column>_enc") @Convert(converter = \
        PiEncryptedStringConverter.class) private String <field>Encrypted;, callback statements ending in \
        "// PI Co-Pilot" (@PrePersist/@PreUpdate copy <field> into <field>Encrypted; @PostLoad sets <field> \
        from <field>Encrypted when it is not null), native queries rewritten to read \
        CASE WHEN x_enc IS NOT NULL THEN x_enc ELSE x END, a decryptor for native query results \
        (PiNativeQueryDecryptor), key configuration and a Flyway migration.

        A reviewer has requested changes. Apply the request to the files, following these rules:
        - Keep the plain fields mapped to their plain columns and written on every save: the base branch, \
        which only knows the plain columns, must keep working as the rollback.
        - Reads come from the encrypted copy. When an entity lacks the copy for an encrypted column, add it \
        exactly in the form above next to the plain field, and add the matching statements (marked \
        "// PI Co-Pilot") to the entity's @PrePersist/@PreUpdate and @PostLoad methods. JPA allows one \
        method per callback event in a class, so extend an existing method rather than adding a second one.
        - Keep the encryption compatible: the _enc column names, PiFieldCipher and the "enc:v1:" format \
        must not change, because data is already encrypted with them.
        Change only what the request asks for, and keep the code compiling. Return the full new content \
        of every file you change or add, and nothing for files you leave unchanged. If the request cannot \
        be met by changing these files (for example it asks for a database change), return no files and \
        explain why in the summary.

        The reviewer's line and file comments are numbered. For every numbered comment, report in \
        "resolutions" whether your changes resolve it, with a one-sentence note saying what you changed \
        (or why it is not resolved).
        """;

    public record FileContext(String path, String content) {}

    public record FileEdit(String path, String content) {}

    /** A numbered reviewer comment on a line or file, to be resolved by this round. */
    public record CommentRef(int number, String location, String body) {}

    public record Resolution(int comment, boolean resolved, String note) {}

    public record Revision(String summary, List<FileEdit> files, List<Resolution> resolutions) {}

    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private volatile AnthropicClient client;

    public CodeReviser(ObjectMapper objectMapper,
                       @Value("${picopilot.ai.api-key:}") String apiKey,
                       @Value("${picopilot.ai.base-url:}") String baseUrl,
                       @Value("${picopilot.ai.model:claude-opus-5-5}") String model) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    /**
     * @param request       the reviewer's comment for this round
     * @param earlierRounds comments from previous rounds, already resolved (context only)
     * @param files         current content of every file in the pull request
     * @param allowedRoots  path prefixes the AI may write to
     * @param comments      the reviewer's numbered line/file comments for this round
     */
    public Revision revise(String request, List<String> earlierRounds, List<FileContext> files, List<String> allowedRoots,
                           List<CommentRef> comments) {
        StringBuilder user = new StringBuilder();
        user.append("Reviewer request:\n")
            .append(request.isBlank() ? "(No overall comment; resolve the numbered comments below.)" : request.trim())
            .append("\n\n");
        if (!comments.isEmpty()) {
            user.append("Review comments to resolve:\n");
            comments.forEach(c -> user.append('[').append(c.number()).append("] ").append(c.location()).append(": ")
                .append(c.body().trim()).append('\n'));
            user.append('\n');
        }
        if (!earlierRounds.isEmpty()) {
            user.append("Earlier review comments, already resolved in these files:\n");
            earlierRounds.forEach(c -> user.append("- ").append(c.trim().replace("\n", " ")).append('\n'));
            user.append('\n');
        }
        user.append("You may only write files under: ").append(String.join(", ", allowedRoots)).append("\n\n");
        user.append("Current files of the pull request, and the application's entities for the encrypted tables:\n");
        for (FileContext f : files) {
            user.append("<file path=\"").append(f.path()).append("\">\n").append(f.content()).append("\n</file>\n");
        }

        MessageCreateParams params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(SYSTEM_PROMPT)
            .outputConfig(OutputConfig.builder()
                .effort(OutputConfig.Effort.HIGH)
                .format(JsonOutputFormat.builder().schema(revisionSchema()).build())
                .build())
            .addUserMessage(user.toString())
            .build();

        Message response;
        try {
            response = client().messages().create(params);
        } catch (AnthropicServiceException e) {
            if (e.statusCode() == 401 || e.statusCode() == 403) {
                throw new IllegalStateException("AI is not configured: the PI Co-Pilot backend has no valid Claude API key. "
                    + "Set ANTHROPIC_API_KEY for the backend, restart it, then press Retry round.", e);
            }
            throw new IllegalStateException("The AI request failed (HTTP " + e.statusCode() + "). Try the round again.", e);
        }
        StopReason stop = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            throw new IllegalStateException("The AI declined this request. Rephrase the review comment and try again.");
        }
        if (StopReason.MAX_TOKENS.equals(stop)) {
            throw new IllegalStateException("The AI response was too long for one round. Split the review comment into smaller requests.");
        }
        String json = response.content().stream()
            .flatMap(block -> block.text().stream())
            .map(t -> t.text())
            .collect(Collectors.joining());
        try {
            Revision revision = objectMapper.readValue(json, Revision.class);
            return new Revision(revision.summary(), revision.files() == null ? List.of() : revision.files(),
                revision.resolutions() == null ? List.of() : revision.resolutions());
        } catch (Exception e) {
            throw new IllegalStateException("The AI returned an unreadable revision", e);
        }
    }

    private AnthropicClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().fromEnv()
                        .timeout(Duration.ofMinutes(10));
                    if (!apiKey.isBlank()) builder.apiKey(apiKey);
                    if (!baseUrl.isBlank()) builder.baseUrl(baseUrl);
                    try {
                        client = builder.build();
                    } catch (Exception e) {
                        throw new IllegalStateException("AI is not configured: set ANTHROPIC_API_KEY for the PI Co-Pilot backend "
                            + "so it can resolve review comments", e);
                    }
                }
            }
        }
        return client;
    }

    private static JsonOutputFormat.Schema revisionSchema() {
        Map<String, Object> fileSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string", "description", "Repository path of the file"),
                "content", Map.of("type", "string", "description", "Complete new content of the file")),
            "required", List.of("path", "content"),
            "additionalProperties", false);
        Map<String, Object> resolutionSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                "comment", Map.of("type", "integer", "description", "Number of the review comment"),
                "resolved", Map.of("type", "boolean", "description", "Whether the changes resolve the comment"),
                "note", Map.of("type", "string", "description", "One sentence: what was changed, or why not")),
            "required", List.of("comment", "resolved", "note"),
            "additionalProperties", false);
        return JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                "summary", Map.of("type", "string", "description", "What was changed to resolve the request, for the reviewer"),
                "files", Map.of("type", "array", "items", fileSchema),
                "resolutions", Map.of("type", "array", "items", resolutionSchema))))
            .putAdditionalProperty("required", JsonValue.from(List.of("summary", "files", "resolutions")))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build();
    }
}
