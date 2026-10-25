package com.picopilot.inventory.model;

import com.picopilot.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "finding_evidence")
@Getter
@Setter
public class FindingEvidence extends BaseEntity {

    @Column(name = "finding_id", nullable = false)
    private UUID findingId;

    @Column(nullable = false, length = 100)
    private String evidenceType; // CODE_REFERENCE, SCHEMA_METADATA, ANNOTATION, ORM_MAPPING, QUERY, API, COLUMN_NAME

    @Column(name = "source_file", length = 500)
    private String sourceFile;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Column(name = "symbol_name", length = 300)
    private String symbolName;

    @Column(name = "code_snippet", columnDefinition = "text")
    private String codeSnippet; // Limited snippet, never full file content

    @Column(name = "description", columnDefinition = "text")
    private String description;
}