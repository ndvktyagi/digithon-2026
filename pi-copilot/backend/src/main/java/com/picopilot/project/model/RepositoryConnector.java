package com.picopilot.project.model;

import com.picopilot.common.model.TenantAwareEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "repository_connector")
@Getter
@Setter
public class RepositoryConnector extends TenantAwareEntity {

    @ManyToOne
    @JoinColumn(name = "project_id", nullable = false)
    private ApplicationProject project;

    @Column(nullable = false, length = 50)
    private String connectorType; // GITHUB_APP, GITHUB_TOKEN

    @Column(nullable = false, length = 300)
    private String repositoryUrl;

    @Column(length = 100)
    private String repositoryOwner;

    @Column(length = 100)
    private String repositoryName;

    @Column(length = 150)
    private String secretReference; // reference to secret manager

    @Column(name = "api_base_url", length = 300)
    private String apiBaseUrl;

    @Column(length = 150)
    private String branch;

    /** Newline-separated folders to scan; null means the whole repository. */
    @Column(name = "include_paths", columnDefinition = "TEXT")
    private String includePaths;

    @JsonIgnore
    @Column(name = "encrypted_token", columnDefinition = "TEXT")
    private String encryptedToken; // AES-GCM, see SecretCipher

    @Column(nullable = false)
    private boolean verified = false;

    @Column(length = 30)
    private String status = "ACTIVE";
}