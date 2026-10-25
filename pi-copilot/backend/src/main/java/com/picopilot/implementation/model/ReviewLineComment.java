package com.picopilot.implementation.model;

import com.picopilot.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** A reviewer's comment on one diff line of an implementation round. */
@Entity
@Table(name = "review_line_comment")
@Getter
@Setter
public class ReviewLineComment extends BaseEntity {

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(nullable = false, length = 500)
    private String path;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    /** NEW for a line of the changed file, OLD for a removed line of the original, FILE for the whole file. */
    @Column(nullable = false, length = 4)
    private String side;

    /** The line's code when the comment was made, so the context survives later rounds. */
    @Column(name = "line_text", columnDefinition = "TEXT")
    private String lineText;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(length = 100)
    private String author;

    /** Set when an improvement round addressed this comment. */
    @Column(nullable = false)
    private boolean resolved;

    @Column(name = "resolved_in_round")
    private Integer resolvedInRound;

    /** How the AI resolved it, shown with the resolved conversation. */
    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "resolved_at")
    private java.time.Instant resolvedAt;
}
