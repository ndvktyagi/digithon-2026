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
@Table(name = "database_connector")
@Getter
@Setter
public class DatabaseConnector extends TenantAwareEntity {

    @ManyToOne
    @JoinColumn(name = "project_id", nullable = false)
    private ApplicationProject project;

    @Column(nullable = false, length = 300)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(nullable = false, length = 100)
    private String databaseName;

    @Column(nullable = false)
    private boolean tlsEnabled = true;

    @Column(length = 50)
    private String tlsMode;

    @Column(length = 150)
    private String usernameSecretReference; // reference to secret manager

    @Column(length = 150)
    private String passwordSecretReference; // reference to secret manager

    @Column(length = 150)
    private String username;

    @JsonIgnore
    @Column(name = "encrypted_password", columnDefinition = "TEXT")
    private String encryptedPassword; // AES-GCM, see SecretCipher

    @Column(length = 500)
    private String schemaScope; // comma-separated or JSON list

    @Column(nullable = false)
    private boolean verified = false;

    @Column(length = 30)
    private String status = "ACTIVE";
}