package com.picopilot.implementation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picopilot.common.event.StageWorkCompletedEvent;
import com.picopilot.common.model.WorkspaceStage;
import com.picopilot.implementation.model.ImplementationFileChange;
import com.picopilot.implementation.model.ImplementationRun;
import com.picopilot.implementation.repository.ImplementationFileChangeRepository;
import com.picopilot.implementation.repository.ImplementationRunRepository;
import com.picopilot.implementation.repository.ReviewLineCommentRepository;
import com.picopilot.implementation.model.ReviewLineComment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Works through Final Review "Improve" requests: each queued round asks the AI to resolve the
 * reviewer's comment, pushes the result to the same branch (updating the pull request) and sends
 * the implementation back to review.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImprovementExecutor {

    private final ImplementationRunRepository runRepository;
    private final ImplementationFileChangeRepository fileChangeRepository;
    private final PullRequestGateway pullRequests;
    private final CodeReviser reviser;
    private final ApplicationEventPublisher events;
    private final ReviewLineCommentRepository lineCommentRepository;
    private final ObjectMapper objectMapper;

    /** Current state of one pull-request file across all rounds so far. */
    private static final class PrFile {
        String baseContent;
        String content;
    }

    @Scheduled(fixedDelayString = "${picopilot.review.poll-interval-ms:20000}",
        initialDelayString = "${picopilot.review.initial-delay-ms:15000}")
    public void processQueuedRounds() {
        for (ImplementationRun run : runRepository.findByStatusAndParentRunIdIsNotNull(ImplementationRun.PENDING)) {
            process(run);
        }
    }

    void process(ImplementationRun run) {
        StringBuilder logText = new StringBuilder();
        try {
            run.setStatus(ImplementationRun.RUNNING);
            run.setCodeStatus(ImplementationRun.RUNNING);
            run.setStartedAt(Instant.now());
            run = save(run, logText, "Round " + run.getRoundNumber() + " started: resolving review comment");

            List<ImplementationRun> chain = chain(run);
            Map<String, PrFile> files = currentFiles(chain);
            List<String> earlier = chain.stream()
                .filter(r -> r.getParentRunId() != null && r.getImprovementRequest() != null)
                .map(ImplementationRun::getImprovementRequest)
                .toList();
            PullRequestGateway.Repo repo = pullRequests.repo(run.getRepositoryConnectorId());
            addEntityFiles(run, repo, files, logText);
            List<String> roots = allowedRoots(repo.includes(), files.keySet());
            appendLog(logText, "Asking the AI to apply the comment to " + files.size() + " file(s) under " + roots);

            // The previous round's line/file comments, numbered so the AI can say which ones it resolved
            ImplementationRun parent = chain.get(chain.size() - 2);
            List<ReviewLineComment> comments = lineCommentRepository.findByRunIdOrderByPathAscLineNumberAsc(parent.getId());
            List<CodeReviser.CommentRef> refs = new ArrayList<>();
            for (int i = 0; i < comments.size(); i++) {
                refs.add(new CodeReviser.CommentRef(i + 1, location(comments.get(i)), comments.get(i).getBody()));
            }
            String request = comments.isEmpty() ? run.getImprovementRequest()
                : parent.getReviewComment() == null ? "" : parent.getReviewComment();

            CodeReviser.Revision revision = reviser.revise(request, earlier,
                files.entrySet().stream().map(e -> new CodeReviser.FileContext(e.getKey(), e.getValue().content)).toList(),
                roots, refs);
            run.setAiSummary(revision.summary());

            Map<String, String> edits = validEdits(revision.files(), files, roots, logText);
            if (edits.isEmpty()) {
                throw new IllegalStateException("The AI made no code changes: " + revision.summary());
            }

            String message = "PI Co-Pilot: review round " + run.getRoundNumber() + "\n\n"
                + "Review comment: " + run.getImprovementRequest().trim() + "\n\n" + revision.summary();
            String commit = pullRequests.commitToBranch(repo, run.getBranchName(), edits, message);
            appendLog(logText, "Pushed commit " + commit.substring(0, 7) + " to " + run.getBranchName());

            UUID runId = run.getId();
            List<String> changed = new ArrayList<>();
            edits.forEach((path, content) -> {
                PrFile before = files.get(path);
                ImplementationFileChange change = new ImplementationFileChange();
                change.setRunId(runId);
                change.setPath(path);
                change.setBaseContent(before == null ? null : before.baseContent);
                change.setPreviousContent(before == null ? null : before.content);
                change.setChangeKind(before == null || before.baseContent == null ? "ADDED" : "MODIFIED");
                change.setNewContent(content);
                fileChangeRepository.save(change);
                changed.add(change.getChangeKind() + "|" + path);
            });

            List<ReviewLineComment> resolved = markResolved(comments, revision.resolutions(), run.getRoundNumber());
            if (!comments.isEmpty()) {
                appendLog(logText, "Resolved " + resolved.size() + " of " + comments.size() + " review comment(s)");
                pullRequests.resolveThreads(repo, run.getPrNumber(), resolved.stream()
                    .map(c -> new PullRequestGateway.ThreadRef(c.getPath(), c.getBody())).toList());
            }

            run.setCommitSha(commit);
            run.setChangedFiles(String.join("\n", changed));
            run.setCodeStatus(ImplementationRun.COMPLETED);
            run.setStatus(ImplementationRun.COMPLETED);
            run.setReviewStatus(ImplementationRun.PENDING_REVIEW);
            run.setCompletedAt(Instant.now());
            run = save(run, logText, "Round " + run.getRoundNumber() + " ready for review");
            pullRequests.comment(repo, run.getPrNumber(), "**PI Co-Pilot review round " + run.getRoundNumber()
                + "**\n\n> " + run.getImprovementRequest().trim().replace("\n", "\n> ") + "\n\n" + revision.summary());
            events.publishEvent(new StageWorkCompletedEvent(run.getWorkspaceId(), WorkspaceStage.IMPLEMENTATION,
                run.getCreatedBy() != null ? run.getCreatedBy() : "system"));
        } catch (Exception e) {
            log.warn("Review round {} failed: {}", run.getId(), e.getMessage());
            run.setStatus(ImplementationRun.FAILED);
            run.setCodeStatus(ImplementationRun.FAILED);
            run.setCompletedAt(Instant.now());
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            run.setErrorMessage(message.length() > 2000 ? message.substring(0, 2000) : message);
            save(run, logText, "Failed: " + message);
        }
    }

    /** Marks the comments the AI reported as resolved and returns them. */
    private List<ReviewLineComment> markResolved(List<ReviewLineComment> comments, List<CodeReviser.Resolution> resolutions,
                                                 int round) {
        List<ReviewLineComment> resolved = new ArrayList<>();
        Instant now = Instant.now();
        for (CodeReviser.Resolution r : resolutions) {
            if (!r.resolved() || r.comment() < 1 || r.comment() > comments.size()) continue;
            ReviewLineComment c = comments.get(r.comment() - 1);
            if (c.isResolved()) continue;
            c.setResolved(true);
            c.setResolvedInRound(round);
            c.setResolutionNote(r.note());
            c.setResolvedAt(now);
            resolved.add(lineCommentRepository.save(c));
        }
        return resolved;
    }

    /** "path line 12 `code`", "path line 3 (original)" or "path (whole file)". */
    static String location(ReviewLineComment c) {
        if ("FILE".equals(c.getSide())) return c.getPath() + " (whole file)";
        String where = c.getPath() + " line " + c.getLineNumber() + ("OLD".equals(c.getSide()) ? " (original)" : "");
        return c.getLineText() == null || c.getLineText().isBlank() ? where : where + " `" + c.getLineText().trim() + "`";
    }

    /** All rounds from the first to this one, oldest first. */
    private List<ImplementationRun> chain(ImplementationRun run) {
        Deque<ImplementationRun> chain = new ArrayDeque<>();
        chain.addFirst(run);
        UUID parent = run.getParentRunId();
        while (parent != null) {
            ImplementationRun p = runRepository.findById(parent)
                .orElseThrow(() -> new IllegalStateException("A previous review round is missing"));
            chain.addFirst(p);
            parent = p.getParentRunId();
        }
        return new ArrayList<>(chain);
    }

    private Map<String, PrFile> currentFiles(List<ImplementationRun> chain) {
        Map<String, PrFile> files = new LinkedHashMap<>();
        for (ImplementationRun r : chain) {
            for (ImplementationFileChange c : fileChangeRepository.findByRunIdOrderByPathAsc(r.getId())) {
                PrFile f = files.computeIfAbsent(c.getPath(), k -> {
                    PrFile nf = new PrFile();
                    nf.baseContent = c.getBaseContent();
                    return nf;
                });
                f.content = c.getNewContent();
            }
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("The implementation has no recorded file changes to revise");
        }
        return files;
    }

    /**
     * The entities of the approved tables are left out of the pull request (existing code is not changed), but
     * reviewers comment on them; give the AI their current content from the branch so it can add to them.
     */
    private void addEntityFiles(ImplementationRun run, PullRequestGateway.Repo repo, Map<String, PrFile> files,
                                StringBuilder logText) {
        if (run.getColumnsJson() == null) return;
        Set<String> paths = new LinkedHashSet<>();
        try {
            for (JsonNode column : objectMapper.readTree(run.getColumnsJson())) {
                String path = column.path("entityFile").asText("");
                if (!path.isEmpty() && !files.containsKey(path)) paths.add(path);
            }
        } catch (Exception e) {
            log.info("Could not read the columns of run {}: {}", run.getId(), e.getMessage());
            return;
        }
        for (String path : paths) {
            String content = pullRequests.readFile(repo, run.getBranchName(), path);
            if (content == null) {
                appendLog(logText, "Could not read " + path + " from " + run.getBranchName());
                continue;
            }
            PrFile f = new PrFile();
            f.baseContent = content;
            f.content = content;
            files.put(path, f);
            appendLog(logText, "Included entity " + path + " for the AI to change if the comment asks for it");
        }
    }

    /** The connector's folders, or the application folders the pull request already touches. */
    static List<String> allowedRoots(List<String> includes, Set<String> prPaths) {
        if (!includes.isEmpty()) {
            return includes.stream().map(i -> i.endsWith("/") ? i : i + "/").toList();
        }
        Set<String> roots = new LinkedHashSet<>();
        for (String path : prPaths) {
            int i = path.indexOf("src/main/");
            roots.add(i < 0 ? "" : path.substring(0, i));
        }
        return List.copyOf(roots);
    }

    /** Keeps edits that are inside the allowed roots, safe paths and actually different. */
    static Map<String, String> validEdits(List<CodeReviser.FileEdit> edits, Map<String, ?> current,
                                          List<String> roots, StringBuilder logText) {
        Map<String, String> valid = new LinkedHashMap<>();
        for (CodeReviser.FileEdit edit : edits) {
            String path = edit.path() == null ? "" : edit.path().replace('\\', '/').replaceAll("^/+", "");
            if (path.isEmpty() || path.contains("..") || edit.content() == null) {
                appendLog(logText, "Ignored invalid AI edit for '" + edit.path() + "'");
                continue;
            }
            if (roots.stream().noneMatch(path::startsWith)) {
                appendLog(logText, "Ignored AI edit outside the application folders: " + path);
                continue;
            }
            Object before = current.get(path);
            String beforeContent = before instanceof PrFile f ? f.content : null;
            if (Objects.equals(beforeContent, edit.content())) continue;
            valid.put(path, edit.content());
        }
        if (!valid.isEmpty()) {
            appendLog(logText, "AI changed " + valid.keySet().stream().collect(Collectors.joining(", ")));
        }
        return valid;
    }

    private ImplementationRun save(ImplementationRun run, StringBuilder logText, String line) {
        appendLog(logText, line);
        run.setLog(logText.toString());
        // save() returns the entity with its new @Version; always continue with the returned instance
        return runRepository.save(run);
    }

    private static void appendLog(StringBuilder logText, String line) {
        logText.append(Instant.now()).append(' ').append(line).append('\n');
    }
}
