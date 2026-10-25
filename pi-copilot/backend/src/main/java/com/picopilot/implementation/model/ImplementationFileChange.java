package com.picopilot.implementation.model;

import com.picopilot.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** One file changed by an implementation round, with the versions needed to show its diff. */
@Entity
@Table(name = "implementation_file_change")
@Getter
@Setter
public class ImplementationFileChange extends BaseEntity {

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(nullable = false, length = 500)
    private String path;

    /** ADDED or MODIFIED relative to the base branch. */
    @Column(name = "change_kind", nullable = false, length = 10)
    private String changeKind;

    /** Content on the base branch; null for files the implementation added. */
    @Column(name = "base_content", columnDefinition = "TEXT")
    private String baseContent;

    /** Content before this round (the previous round's result, or the base for round 1). */
    @Column(name = "previous_content", columnDefinition = "TEXT")
    private String previousContent;

    @Column(name = "new_content", nullable = false, columnDefinition = "TEXT")
    private String newContent;
}
