package com.picopilot.inventory.model;

import com.picopilot.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "finding_relationship")
@Getter
@Setter
public class FindingRelationship extends BaseEntity {

    @Column(name = "source_finding_id", nullable = false)
    private UUID sourceFindingId;

    @Column(name = "target_finding_id", nullable = false)
    private UUID targetFindingId;

    @Column(nullable = false, length = 50)
    private String relationshipType; // FK_RELATION, JOIN_PATTERN, SAME_ENTITY, API_FLOW, QUERY_DEPENDENCY

    @Column(length = 500)
    private String description;
}