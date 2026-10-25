package com.picopilot.inventory.model;

import com.picopilot.common.model.BaseEntity;
import com.picopilot.common.model.ReviewDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finding_review")
@Getter
@Setter
public class FindingReview extends BaseEntity {

    @Column(name = "finding_id", nullable = false)
    private UUID findingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReviewDecision decision;

    @Column(name = "reclassified_category", length = 50)
    private String reclassifiedCategory;

    @Column(columnDefinition = "text")
    private String rationale;

    @Column(name = "reviewer_id", nullable = false, length = 100)
    private String reviewerId;

    @Column(name = "reviewer_name", length = 200)
    private String reviewerName;

    @Column(nullable = false)
    private Instant reviewedAt = Instant.now();
}