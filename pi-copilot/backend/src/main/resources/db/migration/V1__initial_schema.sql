-- V1__initial_schema.sql
-- PI Security Copilot - Initial Database Schema
-- PostgreSQL 16+ required

-- Enable extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Workspace
CREATE TABLE workspace (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL UNIQUE,
    slug VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Workspace Member
CREATE TABLE workspace_member (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    user_id VARCHAR(100) NOT NULL,
    user_name VARCHAR(200),
    user_email VARCHAR(200),
    role VARCHAR(30) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    UNIQUE(workspace_id, user_id)
);

-- Application Project
CREATE TABLE application_project (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    name VARCHAR(150) NOT NULL,
    description VARCHAR(500),
    target_language VARCHAR(50),
    database_type VARCHAR(50),
    default_branch VARCHAR(100),
    status VARCHAR(30) DEFAULT 'ACTIVE',
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Repository Connector
CREATE TABLE repository_connector (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    project_id UUID NOT NULL REFERENCES application_project(id),
    connector_type VARCHAR(50) NOT NULL,
    repository_url VARCHAR(300) NOT NULL,
    repository_owner VARCHAR(100),
    repository_name VARCHAR(100),
    secret_reference VARCHAR(150),
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(30) DEFAULT 'ACTIVE',
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Database Connector
CREATE TABLE database_connector (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    project_id UUID NOT NULL REFERENCES application_project(id),
    host VARCHAR(300) NOT NULL,
    port INTEGER NOT NULL,
    database_name VARCHAR(100) NOT NULL,
    tls_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    tls_mode VARCHAR(50),
    username_secret_reference VARCHAR(150) NOT NULL,
    password_secret_reference VARCHAR(150) NOT NULL,
    schema_scope VARCHAR(500),
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(30) DEFAULT 'ACTIVE',
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Scan Job
CREATE TABLE scan_job (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    project_id UUID NOT NULL REFERENCES application_project(id),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    scan_type VARCHAR(30) NOT NULL,
    analysis_engine VARCHAR(50),
    analysis_version VARCHAR(50),
    repository_commit VARCHAR(40),
    total_files_scanned INTEGER,
    total_tables_scanned INTEGER,
    findings_count INTEGER,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    error_message VARCHAR(2000),
    idempotency_key VARCHAR(100) UNIQUE,
    scope_snapshot JSONB,
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Scan Scope
CREATE TABLE scan_scope (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    scan_job_id UUID NOT NULL REFERENCES scan_job(id),
    repository_paths JSONB,
    excluded_paths JSONB,
    schemas JSONB,
    tables JSONB,
    excluded_tables JSONB,
    value_pattern_scan_enabled BOOLEAN DEFAULT FALSE,
    max_file_size_bytes BIGINT,
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- PI Finding
CREATE TABLE pi_finding (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    project_id UUID NOT NULL REFERENCES application_project(id),
    scan_job_id UUID NOT NULL REFERENCES scan_job(id),
    db_schema VARCHAR(100),
    table_name VARCHAR(200),
    column_name VARCHAR(200),
    data_type VARCHAR(100),
    nullable BOOLEAN NOT NULL DEFAULT FALSE,
    is_primary_key BOOLEAN DEFAULT FALSE,
    is_foreign_key BOOLEAN DEFAULT FALSE,
    foreign_key_reference VARCHAR(500),
    candidate_category VARCHAR(50),
    confidence VARCHAR(10) NOT NULL,
    detection_method VARCHAR(100),
    detector_rule VARCHAR(200),
    reasoning TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'DISCOVERED',
    suggested_action VARCHAR(50),
    impact_summary TEXT,
    reviewer_comment TEXT,
    reviewed_by VARCHAR(100),
    reviewed_at TIMESTAMP WITH TIME ZONE,
    analysis_engine VARCHAR(50),
    analysis_version VARCHAR(50),
    field_order_hash VARCHAR(64) UNIQUE,
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Finding Evidence
CREATE TABLE finding_evidence (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    finding_id UUID NOT NULL REFERENCES pi_finding(id),
    evidence_type VARCHAR(100) NOT NULL,
    source_file VARCHAR(500),
    line_number INTEGER,
    symbol_name VARCHAR(300),
    code_snippet TEXT,
    description TEXT,
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Finding Relationship
CREATE TABLE finding_relationship (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_finding_id UUID NOT NULL REFERENCES pi_finding(id),
    target_finding_id UUID NOT NULL REFERENCES pi_finding(id),
    relationship_type VARCHAR(50) NOT NULL,
    description VARCHAR(500),
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Finding Review
CREATE TABLE finding_review (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    finding_id UUID NOT NULL REFERENCES pi_finding(id),
    decision VARCHAR(30) NOT NULL,
    reclassified_category VARCHAR(50),
    rationale TEXT,
    reviewer_id VARCHAR(100) NOT NULL,
    reviewer_name VARCHAR(200),
    reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Encryption Provider Config
CREATE TABLE encryption_provider_config (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    provider_type VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    endpoint VARCHAR(500),
    key_name VARCHAR(200),
    key_id VARCHAR(100),
    key_version VARCHAR(50),
    auth_method VARCHAR(100),
    auth_reference VARCHAR(150),
    rotation_policy_days INTEGER,
    timeout_seconds INTEGER DEFAULT 10,
    retry_policy VARCHAR(200),
    health_check_enabled BOOLEAN DEFAULT TRUE,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    is_development BOOLEAN DEFAULT FALSE,
    status VARCHAR(30) DEFAULT 'ACTIVE',
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Key Metadata
CREATE TABLE key_metadata (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    provider_config_id UUID NOT NULL REFERENCES encryption_provider_config(id),
    key_alias VARCHAR(100) NOT NULL,
    key_identifier VARCHAR(200),
    key_version INTEGER NOT NULL,
    key_type VARCHAR(30) NOT NULL,
    key_state VARCHAR(20) DEFAULT 'ACTIVE',
    activated_at TIMESTAMP WITH TIME ZONE,
    retired_at TIMESTAMP WITH TIME ZONE,
    rotation_policy JSONB,
    wrapped_key_reference VARCHAR(200),
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Migration Plan
CREATE TABLE migration_plan (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    project_id UUID NOT NULL REFERENCES application_project(id),
    provider_config_id UUID REFERENCES encryption_provider_config(id),
    name VARCHAR(200) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PREVIEW',
    ddl_script TEXT,
    rollback_ddl TEXT,
    impacted_tables JSONB,
    impacted_queries JSONB,
    estimated_row_count BIGINT,
    test_plan TEXT,
    rollback_plan TEXT,
    approved_by VARCHAR(100),
    approved_at TIMESTAMP WITH TIME ZONE,
    pr_branch_name VARCHAR(200),
    pr_number INTEGER,
    pr_url VARCHAR(500),
    feature_flag_name VARCHAR(100),
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Generated Patch
CREATE TABLE generated_patch (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    migration_plan_id UUID NOT NULL REFERENCES migration_plan(id),
    file_path VARCHAR(500) NOT NULL,
    patch_type VARCHAR(30) NOT NULL,
    original_content TEXT,
    patched_content TEXT,
    description TEXT,
    is_manual BOOLEAN DEFAULT FALSE,
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Approval Record
CREATE TABLE approval_record (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    target_type VARCHAR(30) NOT NULL,
    target_id UUID NOT NULL,
    approver_id VARCHAR(100) NOT NULL,
    approver_name VARCHAR(200),
    action VARCHAR(20) NOT NULL,
    comment TEXT,
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Backfill Job
CREATE TABLE backfill_job (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    migration_plan_id UUID NOT NULL REFERENCES migration_plan(id),
    project_id UUID NOT NULL REFERENCES application_project(id),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    total_rows BIGINT,
    processed_rows BIGINT,
    failed_rows BIGINT,
    current_checkpoint JSONB,
    batch_size INTEGER DEFAULT 1000,
    max_retries INTEGER DEFAULT 3,
    retry_count INTEGER DEFAULT 0,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    error_message TEXT,
    idempotency_key VARCHAR(100) UNIQUE,
    worker_node VARCHAR(100),
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Backfill Batch
CREATE TABLE backfill_batch (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    backfill_job_id UUID NOT NULL REFERENCES backfill_job(id),
    batch_number INTEGER NOT NULL,
    start_range JSONB,
    end_range JSONB,
    row_count INTEGER DEFAULT 0,
    processed_count INTEGER DEFAULT 0,
    error_count INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    error_details JSONB,
    retry_count INTEGER DEFAULT 0,
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Verification Run
CREATE TABLE verification_run (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    backfill_job_id UUID NOT NULL REFERENCES backfill_job(id),
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    rows_checked BIGINT,
    decrypt_success_count BIGINT,
    decrypt_fail_count BIGINT,
    row_count_match BOOLEAN DEFAULT FALSE,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    result_detail JSONB,
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Feature Flag
CREATE TABLE feature_flag (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    project_id UUID NOT NULL REFERENCES application_project(id),
    migration_plan_id UUID REFERENCES migration_plan(id),
    flag_name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    scope VARCHAR(30) NOT NULL,
    scope_target VARCHAR(200),
    current_state VARCHAR(20) NOT NULL DEFAULT 'DISABLED',
    target_state VARCHAR(20),
    rollback_window_hours INTEGER DEFAULT 72,
    readiness_checks JSONB,
    enabled_at TIMESTAMP WITH TIME ZONE,
    enabled_by VARCHAR(100),
    rollback_at TIMESTAMP WITH TIME ZONE,
    rollback_by VARCHAR(100),
    rollback_reason TEXT,
    approval_required BOOLEAN DEFAULT TRUE,
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Audit Event
CREATE TABLE audit_event (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id UUID REFERENCES workspace(id),
    project_id UUID REFERENCES application_project(id),
    actor VARCHAR(100) NOT NULL,
    actor_role VARCHAR(30),
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id VARCHAR(100) NOT NULL,
    target_name VARCHAR(300),
    outcome VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    severity VARCHAR(10) DEFAULT 'INFO',
    correlation_id VARCHAR(50),
    job_id UUID,
    event_metadata JSONB,
    source_ip VARCHAR(50),
    event_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Indexes for performance
CREATE INDEX idx_workspace_member_user ON workspace_member(user_id);
CREATE INDEX idx_workspace_member_ws_user ON workspace_member(workspace_id, user_id);
CREATE INDEX idx_project_workspace ON application_project(workspace_id);
CREATE INDEX idx_repo_connector_project ON repository_connector(project_id);
CREATE INDEX idx_db_connector_project ON database_connector(project_id);
CREATE INDEX idx_scan_job_project ON scan_job(project_id);
CREATE INDEX idx_pi_finding_project ON pi_finding(project_id);
CREATE INDEX idx_pi_finding_scan ON pi_finding(scan_job_id);
CREATE INDEX idx_pi_finding_status ON pi_finding(status);
CREATE INDEX idx_pi_finding_category ON pi_finding(candidate_category);
CREATE INDEX idx_finding_evidence_finding ON finding_evidence(finding_id);
CREATE INDEX idx_finding_review_finding ON finding_review(finding_id);
CREATE INDEX idx_encryption_provider_ws ON encryption_provider_config(workspace_id);
CREATE INDEX idx_key_metadata_provider ON key_metadata(provider_config_id);
CREATE INDEX idx_migration_plan_project ON migration_plan(project_id);
CREATE INDEX idx_backfill_job_project ON backfill_job(project_id);
CREATE INDEX idx_backfill_batch_job ON backfill_batch(backfill_job_id);
CREATE INDEX idx_audit_event_workspace ON audit_event(workspace_id);
CREATE INDEX idx_audit_event_project ON audit_event(project_id);
CREATE INDEX idx_audit_event_action ON audit_event(action);
CREATE INDEX idx_audit_event_time ON audit_event(event_time DESC);
CREATE INDEX idx_feature_flag_project ON feature_flag(project_id);