package com.picopilot.implementation.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JpaEntityAnalyzerTest {

    private static final String CONVERTER = "com.digithon.hr.security.PiEncryptedStringConverter";

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

    private static Map<String, String> fields(String... columnAndField) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < columnAndField.length; i += 2) m.put(columnAndField[i], columnAndField[i + 1]);
        return m;
    }

    @Test
    void mapsEntityToTable() {
        assertThat(JpaEntityAnalyzer.isEntity(EMPLOYEE)).isTrue();
        assertThat(JpaEntityAnalyzer.mapsTable(EMPLOYEE, "employees")).isTrue();
        assertThat(JpaEntityAnalyzer.mapsTable(EMPLOYEE, "departments")).isFalse();
        assertThat(JpaEntityAnalyzer.className(EMPLOYEE)).isEqualTo("com.digithon.hr.entity.Employee");
    }

    @Test
    void mapsColumnsToFields() {
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
    void skipsColumnsWhoseEncryptedColumnTheEntityAlreadyMapsItsOwnWay() {
        String mapped = EMPLOYEE.replace("    protected Employee() { }",
            "    @Column(name = \"email_enc\")\n    private String emailEnc;\n\n    protected Employee() { }");
        JpaEntityAnalyzer.Result r = JpaEntityAnalyzer.analyze(mapped, List.of("email"));
        assertThat(r.fields()).isEmpty();
        assertThat(r.columnOutcomes()).containsEntry("email", "ALREADY_PRESENT");
    }

    @Test
    void reportsColumnsAnEarlierReleaseAlreadyRetired() {
        String retired = EMPLOYEE.replace("    @Column(name = \"first_name\", nullable = false, length = 80)\n",
            "    @Column(name = \"first_name_enc\", nullable = false)\n    @Convert(converter = PiEncryptedStringConverter.class)\n");
        JpaEntityAnalyzer.Result r = JpaEntityAnalyzer.analyze(retired, List.of("first_name", "email"));
        assertThat(r.fields()).containsExactlyEntriesOf(Map.of("email", "email"));
        assertThat(r.columnOutcomes()).containsEntry("first_name", "RETIRED:firstName").containsEntry("email", "MAPPED:email");
        assertThat(JpaEntityAnalyzer.mapsEncryptedColumn(retired, "firstName", "first_name")).isTrue();
    }

    @Test
    void writesBothColumnsAndReadsTheEncryptedOne() {
        String src = JpaEntityAnalyzer.withEncryptedFields(EMPLOYEE, fields("email", "email", "phone", "phone"), CONVERTER);
        // the plain fields keep their mapping, so the plain columns stay current for the rollback
        assertThat(src).contains("    @Column(nullable = false, length = 180)\n    private String email;\n")
            .contains("    @Column(name = \"email_enc\")\n    @Convert(converter = PiEncryptedStringConverter.class)\n"
                + "    private String emailEncrypted;\n")
            .contains("    @Column(name = \"phone_enc\")\n    @Convert(converter = PiEncryptedStringConverter.class)\n"
                + "    private String phoneEncrypted;\n")
            .contains("    @PrePersist\n    @PreUpdate\n    void piCopilotEncrypt() {\n"
                + "        this.emailEncrypted = this.email; // PI Co-Pilot\n"
                + "        this.phoneEncrypted = this.phone; // PI Co-Pilot\n    }\n")
            .contains("    @PostLoad\n    void piCopilotDecrypt() {\n"
                + "        if (this.emailEncrypted != null) this.email = this.emailEncrypted; // PI Co-Pilot\n")
            .contains("import com.digithon.hr.security.PiEncryptedStringConverter;")
            .endsWith("    }\n}\n");
        // only additions: every original line is still there, in order
        assertThat(isSubsequence(EMPLOYEE.lines().toList(), src.lines().toList())).isTrue();
    }

    @Test
    void extendsExistingCallbacksInsteadOfAddingSecondOnes() {
        String withCallback = EMPLOYEE.replace("    protected Employee() { }",
            "    @PrePersist\n    void onCreate() {\n        joiningDate = LocalDate.now();\n    }\n\n    protected Employee() { }");
        String src = JpaEntityAnalyzer.withEncryptedFields(withCallback, fields("email", "email"), CONVERTER);
        assertThat(src).contains("    @PrePersist\n    void onCreate() {\n        this.emailEncrypted = this.email; // PI Co-Pilot\n"
            + "        joiningDate = LocalDate.now();\n    }");
        // JPA allows one method per event: the generated method only covers the missing ones
        assertThat(count(src, "@PrePersist")).isEqualTo(1);
        assertThat(src).contains("    @PreUpdate\n    void piCopilotEncrypt() {").contains("    @PostLoad\n    void piCopilotDecrypt() {");
    }

    @Test
    void isIdempotentAndAddsLaterColumnsToTheSameMethods() {
        String once = JpaEntityAnalyzer.withEncryptedFields(EMPLOYEE, fields("email", "email"), CONVERTER);
        assertThat(JpaEntityAnalyzer.withEncryptedFields(once, fields("email", "email"), CONVERTER)).isEqualTo(once);
        assertThat(JpaEntityAnalyzer.analyze(once, List.of("email")).columnOutcomes()).containsEntry("email", "MAPPED:email");

        String twice = JpaEntityAnalyzer.withEncryptedFields(once, fields("email", "email", "phone", "phone"), CONVERTER);
        assertThat(count(twice, "void piCopilotEncrypt()")).isEqualTo(1);
        assertThat(count(twice, "void piCopilotDecrypt()")).isEqualTo(1);
        assertThat(twice).contains("this.phoneEncrypted = this.phone; // PI Co-Pilot")
            .contains("if (this.phoneEncrypted != null) this.phone = this.phoneEncrypted; // PI Co-Pilot");
        assertThat(count(twice, "this.emailEncrypted = this.email;")).isEqualTo(1);
    }

    @Test
    void addsImportsWhenThereIsNoWildcard() {
        String explicit = EMPLOYEE.replace("import jakarta.persistence.*;", "import jakarta.persistence.Entity;");
        String src = JpaEntityAnalyzer.withEncryptedFields(explicit, fields("email", "email"), CONVERTER);
        assertThat(src).contains("import jakarta.persistence.Column;").contains("import jakarta.persistence.Convert;")
            .contains("import jakarta.persistence.PrePersist;").contains("import jakarta.persistence.PreUpdate;")
            .contains("import jakarta.persistence.PostLoad;");
    }

    @Test
    void generatedClassesHaveNoFlag() {
        String cipher = EncryptionCodeTemplates.cipher("com.digithon.hr.security");
        assertThat(cipher).contains("public PiFieldCipher(Environment environment)").doesNotContain("@Value")
            .doesNotContain("encrypted read");
        assertThat(EncryptionCodeTemplates.converter("com.digithon.hr.security", "jakarta.persistence"))
            .contains("implements AttributeConverter<String, String>").doesNotContain("PiEncryptionFlags");
        assertThat(EncryptionCodeTemplates.migration(List.of("ALTER TABLE x ADD COLUMN IF NOT EXISTS y_enc text;")))
            .doesNotContain("pi_feature_flag");
    }

    /** True if every line of {@code original} appears in {@code updated}, in the same order. */
    private static boolean isSubsequence(List<String> original, List<String> updated) {
        int i = 0;
        for (String line : updated) {
            if (i < original.size() && original.get(i).equals(line)) i++;
        }
        return i == original.size();
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }
}
