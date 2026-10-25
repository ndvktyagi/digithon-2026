package com.picopilot.scanner.model;

import com.picopilot.common.model.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "scan_scope")
@Getter
@Setter
public class ScanScope extends TenantAwareEntity {

    @Column(name = "scan_job_id", nullable = false)
    private UUID scanJobId;

    @Column(name = "repository_paths", columnDefinition = "jsonb")
    private String repositoryPaths; // JSON array of included paths

    @Column(name = "excluded_paths", columnDefinition = "jsonb")
    private String excludedPaths;

    @Column(name = "schemas", columnDefinition = "jsonb")
    private String schemas; // JSON array of schema names

    @Column(name = "tables", columnDefinition = "jsonb")
    private String tables; // JSON array of table names (null = all)

    @Column(name = "excluded_tables", columnDefinition = "jsonb")
    private String excludedTables;

    @Column(name = "value_pattern_scan_enabled")
    private boolean valuePatternScanEnabled = false;

    @Column(name = "max_file_size_bytes")
    private Long maxFileSizeBytes;
}