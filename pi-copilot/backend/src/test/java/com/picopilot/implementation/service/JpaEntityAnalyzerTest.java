package com.picopilot.implementation.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JpaEntityAnalyzerTest {

    private static final String EMPLOYEE = """
        package com.digithon.hr.entity;

        import jakarta.persistence.*;
        import java.time.LocalDate;

        @Entity
        @Table(name = "employees", uniqueConstraints = @UniqueConstraint(name = "uk_employees_email", columnNames = "email"))
        public class Employee {
            @Id
            @GeneratedValue(strategy = GenerationType.IDENTITY)
            private Long id;

            @Column(name = "first_name", nullable = false, length = 80)
            private String firstName;

            @Column(nullable = false, length = 180)
            private String email;

            // Personal data used for the future DPDP scanning demonstration.
            @Column(length = 25)
            private String phone;

            @ManyToOne(fetch = FetchType.LAZY)
            @JoinColumn(name = "department_id", nullable = false, foreignKey = @ForeignKey(name = "fk_employees_department"))
            private Department department;

            @Column(name = "joining_date", nullable = false)
            private LocalDate joiningDate;

            protected Employee() { }

            public Long getId() { return id; }
            public String getEmail() { return email; }
        }
        """;

    @Test
    void mapsEntityToTable() {
        assertThat(JpaEntityAnalyzer.isEntity(EMPLOYEE)).isTrue();
        assertThat(JpaEntityAnalyzer.mapsTable(EMPLOYEE, "employees")).isTrue();
        assertThat(JpaEntityAnalyzer.mapsTable(EMPLOYEE, "departments")).isFalse();
        assertThat(JpaEntityAnalyzer.className(EMPLOYEE)).isEqualTo("com.digithon.hr.entity.Employee");
    }

    @Test
    void mapsColumnsToFieldsWithoutChangingTheEntity() {
        JpaEntityAnalyzer.Result r = JpaEntityAnalyzer.analyze(EMPLOYEE, List.of("phone", "email", "first_name"));
        assertThat(r.entityName()).isEqualTo("com.digithon.hr.entity.Employee");
        assertThat(r.fields()).containsExactlyInAnyOrderEntriesOf(
            Map.of("phone", "phone", "email", "email", "first_name", "firstName"));
        assertThat(r.columnOutcomes()).containsEntry("email", "MAPPED:email").containsEntry("first_name", "MAPPED:firstName");
    }

    @Test
    void reportsColumnsItCannotMap() {
        JpaEntityAnalyzer.Result r = JpaEntityAnalyzer.analyze(EMPLOYEE, List.of("joining_date", "ssn"));
        assertThat(r.fields()).isEmpty();
        assertThat(r.columnOutcomes()).containsEntry("joining_date", "UNSUPPORTED_TYPE:LocalDate")
            .containsEntry("ssn", "NO_FIELD");
    }

    @Test
    void skipsColumnsWhoseEncryptedTwinTheEntityAlreadyMaps() {
        String mapped = EMPLOYEE.replace("    protected Employee() { }",
            "    @Column(name = \"email_enc\")\n    private String emailEnc;\n\n    protected Employee() { }");
        JpaEntityAnalyzer.Result r = JpaEntityAnalyzer.analyze(mapped, List.of("email"));
        assertThat(r.fields()).isEmpty();
        assertThat(r.columnOutcomes()).containsEntry("email", "ALREADY_PRESENT");
    }

    @Test
    void generatedRegistryKeepsEarlierEntries() {
        String first = EncryptionCodeTemplates.encryptedFields("com.digithon.hr.security",
            Map.of("com.digithon.hr.entity.Employee", Map.of("email", "email_enc")), null);
        assertThat(first).contains("add(\"com.digithon.hr.entity.Employee\", \"email\", \"email_enc\");");
        String second = EncryptionCodeTemplates.encryptedFields("com.digithon.hr.security",
            Map.of("com.digithon.hr.entity.Employee", Map.of("phone", "phone_enc")), first);
        assertThat(second).contains("\"email\", \"email_enc\"").contains("\"phone\", \"phone_enc\"");
    }

    @Test
    void generatedCodeOnlyActsWhileTheFlagIsOn() {
        String listener = EncryptionCodeTemplates.listener("com.digithon.hr.security", "jakarta.persistence");
        assertThat(listener).contains("import jakarta.persistence.EntityManagerFactory;");
        // every event handler starts with the flag check
        assertThat(listener).contains("public void onPostInsert(PostInsertEvent event) {\n        if (PiEncryptionFlags.encryptedRead()) {")
            .contains("public void onPostUpdate(PostUpdateEvent event) {\n        if (PiEncryptionFlags.encryptedRead()) {")
            .contains("public void onPostLoad(PostLoadEvent event) {\n        if (!PiEncryptionFlags.encryptedRead()) return;");
        String router = EncryptionCodeTemplates.nativeQueryRouter("com.digithon.hr.security");
        assertThat(router).contains("if (!PiEncryptionFlags.encryptedRead()) return invocation.proceed();");
        // the cipher no longer needs the key at startup
        assertThat(EncryptionCodeTemplates.cipher("com.digithon.hr.security"))
            .contains("public PiFieldCipher(Environment environment)").doesNotContain("@Value");
    }
}
