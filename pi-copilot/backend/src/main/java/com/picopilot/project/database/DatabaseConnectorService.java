package com.picopilot.project.database;

import com.picopilot.common.crypto.SecretCipher;
import com.picopilot.common.exception.ValidationException;
import com.picopilot.common.net.HostPolicy;
import com.picopilot.identity.service.WorkspaceService;
import com.picopilot.project.model.ApplicationProject;
import com.picopilot.project.model.DatabaseConnector;
import com.picopilot.project.repository.ApplicationProjectRepository;
import com.picopilot.project.repository.DatabaseConnectorRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseConnectorService {

    private static final Pattern DATABASE_NAME = Pattern.compile("^[A-Za-z0-9_$.-]{1,100}$");
    private static final Set<String> SSL_MODES = Set.of("disable", "prefer", "require", "verify-ca", "verify-full");
    private static final Set<String> SYSTEM_SCHEMAS = Set.of("information_schema", "pg_catalog", "pg_toast");

    private final HostPolicy hostPolicy;
    private final SecretCipher secretCipher;
    private final WorkspaceService workspaceService;
    private final ApplicationProjectRepository projectRepository;
    private final DatabaseConnectorRepository connectorRepository;

    public record ConnectionRequest(String projectName,
                                    @NotBlank String host,
                                    @Min(1) @Max(65535) int port,
                                    @NotBlank String databaseName,
                                    @NotBlank String sslMode,
                                    @NotBlank String username,
                                    @NotBlank String password,
                                    String schemaScope) {}

    public record TestResult(boolean success, String serverVersion, List<String> schemas) {}

    public record ConnectorSummary(UUID id, UUID projectId, String projectName, String host, int port,
                                   String databaseName, String sslMode, String username,
                                   String schemaScope, boolean verified, String status, Instant createdAt) {}

    public TestResult test(UUID workspaceId, ConnectionRequest request, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        validate(request);
        try (Connection connection = open(request)) {
            String version = connection.getMetaData().getDatabaseProductVersion();
            List<String> schemas = new ArrayList<>();
            try (ResultSet rs = connection.getMetaData().getSchemas()) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    if (!SYSTEM_SCHEMAS.contains(schema) && !schema.startsWith("pg_temp") && !schema.startsWith("pg_toast")) {
                        schemas.add(schema);
                    }
                }
            }
            log.info("User {} tested database connection to {}:{}/{}", userId,
                request.host(), request.port(), request.databaseName());
            return new TestResult(true, version, schemas);
        } catch (SQLException e) {
            throw new ValidationException(describe(e, request));
        }
    }

    @Transactional
    public ConnectorSummary save(UUID workspaceId, ConnectionRequest request, String userId) {
        if (request.projectName() == null || request.projectName().isBlank()) {
            throw new ValidationException("Application / project name is required");
        }
        test(workspaceId, request, userId);

        String projectName = request.projectName().trim();
        ApplicationProject project = projectRepository.findByWorkspaceId(workspaceId).stream()
            .filter(p -> p.getName().equalsIgnoreCase(projectName))
            .findFirst()
            .orElseGet(() -> {
                ApplicationProject p = new ApplicationProject();
                p.setWorkspaceId(workspaceId);
                p.setName(projectName);
                return p;
            });
        project.setDatabaseType("POSTGRESQL");
        project = projectRepository.save(project);

        UUID projectId = project.getId();
        DatabaseConnector connector = connectorRepository.findByProjectId(projectId).stream()
            .filter(c -> c.getHost().equalsIgnoreCase(request.host()) && c.getPort() == request.port()
                && c.getDatabaseName().equals(request.databaseName()))
            .findFirst()
            .orElseGet(DatabaseConnector::new);
        connector.setWorkspaceId(workspaceId);
        connector.setProject(project);
        connector.setHost(request.host());
        connector.setPort(request.port());
        connector.setDatabaseName(request.databaseName());
        connector.setTlsMode(request.sslMode());
        connector.setTlsEnabled(!"disable".equals(request.sslMode()));
        connector.setUsername(request.username());
        connector.setEncryptedPassword(secretCipher.encrypt(request.password()));
        connector.setSchemaScope(request.schemaScope());
        connector.setVerified(true);
        connector.setStatus("ACTIVE");
        connector = connectorRepository.save(connector);

        log.info("User {} saved database connector {} for project {}", userId, connector.getId(), projectName);
        return toSummary(connector);
    }

    @Transactional(readOnly = true)
    public List<ConnectorSummary> list(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return connectorRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
            .map(this::toSummary)
            .toList();
    }

    private void validate(ConnectionRequest request) {
        hostPolicy.requireAllowed(request.host());
        if (!DATABASE_NAME.matcher(request.databaseName()).matches()) {
            throw new ValidationException("Invalid database name");
        }
        if (!SSL_MODES.contains(request.sslMode())) {
            throw new ValidationException("Invalid TLS mode");
        }
    }

    private Connection open(ConnectionRequest request) throws SQLException {
        // Only whitelisted driver properties are set; host and database name are validated
        // above so no extra JDBC URL parameters (e.g. socketFactory) can be injected.
        Properties props = new Properties();
        props.setProperty("user", request.username());
        props.setProperty("password", request.password());
        props.setProperty("sslmode", request.sslMode());
        props.setProperty("connectTimeout", "10");
        props.setProperty("socketTimeout", "30");
        props.setProperty("readOnly", "true");
        props.setProperty("ApplicationName", "pi-copilot-connector-test");
        String url = "jdbc:postgresql://" + request.host() + ":" + request.port() + "/" + request.databaseName();
        Connection connection = DriverManager.getConnection(url, props);
        connection.setReadOnly(true);
        return connection;
    }

    private String describe(SQLException e, ConnectionRequest request) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("does not support SSL")) {
            return "The database server does not support TLS. Choose TLS mode 'prefer' or 'disable' for local databases.";
        }
        if (state.equals("28P01") || state.equals("28000")) {
            return "Authentication failed for user '" + request.username() + "'";
        }
        if (state.equals("3D000")) {
            return "Database '" + request.databaseName() + "' does not exist";
        }
        if (state.startsWith("08")) {
            return "Could not connect to " + request.host() + ":" + request.port() + " (" + firstLine(message) + ")";
        }
        return "Connection failed: " + firstLine(message);
    }

    private static String firstLine(String message) {
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private ConnectorSummary toSummary(DatabaseConnector c) {
        return new ConnectorSummary(c.getId(), c.getProject().getId(), c.getProject().getName(), c.getHost(),
            c.getPort(), c.getDatabaseName(), c.getTlsMode(), c.getUsername(), c.getSchemaScope(),
            c.isVerified(), c.getStatus(), c.getCreatedAt());
    }
}
