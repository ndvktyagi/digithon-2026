package com.picopilot.implementation.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Release cleanup rewrites: entities, native queries and the warnings for queries on encrypted fields. */
class FinalizeCodeTest {

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

            @Column(nullable = false, length = 180)
            private String email;

            // Personal data used for the future DPDP scanning demonstration.
            @Column(length = 25)
            private String phone;

            private String nickname;

            protected Employee() { }

            public String getEmail() { return email; }
        }
        """;

    private static final String REPOSITORY = """
        package com.digithon.hr.repository;

        import com.digithon.hr.entity.Employee;
        import org.springframework.data.jpa.repository.JpaRepository;
        import org.springframework.data.jpa.repository.Query;

        import java.util.List;

        public interface EmployeeRepository extends JpaRepository<Employee, Long> {
            boolean existsByEmailIgnoreCase(String email);

            List<Employee> findByLastName(String lastName);

            @Query(value = \"""
                SELECT e.id, e.first_name, e.email, e.phone AS mobile
                FROM employees e
                ORDER BY e.id\""", nativeQuery = true)
            List<Object[]> findAllRows();
        }
        """;

    /** The entity after Implementation: plain fields, their encrypted copies and the generated callbacks. */
    private static String implemented() {
        return JpaEntityAnalyzer.withEncryptedFields(EMPLOYEE, Map.of("email", "email", "phone", "phone"), CONVERTER);
    }

    @Test
    void movesFieldsToTheEncryptedColumnsAndRemovesTheCopiesAndCallbacks() {
        String src = JpaEntityAnalyzer.withPlainColumnsRemoved(implemented(),
            Map.of("email", "email", "phone", "phone", "nickname", "nickname"), CONVERTER);

        assertThat(src).doesNotContain("Encrypted;").doesNotContain("Encrypted =").doesNotContain("encrypted copy of")
            .doesNotContain("@PrePersist").doesNotContain("@PostLoad").doesNotContain("piCopilot")
            .doesNotContain(JpaEntityAnalyzer.MARKER + "\n");
        assertThat(src).contains("    @Column(name = \"email_enc\", nullable = false)\n"
                + "    @Convert(converter = PiEncryptedStringConverter.class)\n    private String email;")
            .contains("    @Column(name = \"phone_enc\")\n    @Convert(converter = PiEncryptedStringConverter.class)\n    private String phone;")
            // a field without @Column gets one
            .contains("    @Column(name = \"nickname_enc\")\n    @Convert(converter = PiEncryptedStringConverter.class)\n    private String nickname;")
            .contains("import com.digithon.hr.security.PiEncryptedStringConverter;")
            // the unique key on the plain column goes with it
            .contains("@Table(name = \"employees\")")
            .contains("public String getEmail() { return email; }");
        for (String field : List.of("email", "phone", "nickname")) {
            assertThat(JpaEntityAnalyzer.mapsEncryptedColumn(src, field, field)).isTrue();
        }
        // the existing comment above phone is kept
        assertThat(src).contains("// Personal data used for the future DPDP scanning demonstration.");
    }

    @Test
    void entityCleanupIsIdempotent() {
        Map<String, String> fields = Map.of("email", "email", "phone", "phone");
        String once = JpaEntityAnalyzer.withPlainColumnsRemoved(implemented(), fields, CONVERTER);
        assertThat(JpaEntityAnalyzer.withPlainColumnsRemoved(once, fields, CONVERTER)).isEqualTo(once);
    }

    @Test
    void keepsOtherTableConstraints() {
        String entity = implemented().replace(
            "uniqueConstraints = @UniqueConstraint(name = \"uk_employees_email\", columnNames = \"email\")",
            "uniqueConstraints = {@UniqueConstraint(name = \"uk_email\", columnNames = \"email\"), "
                + "@UniqueConstraint(name = \"uk_code\", columnNames = \"code\")}");
        String src = JpaEntityAnalyzer.withPlainColumnsRemoved(entity, Map.of("email", "email"), CONVERTER);
        assertThat(src).contains("@Table(name = \"employees\", uniqueConstraints = {@UniqueConstraint(name = \"uk_code\", columnNames = \"code\")})");
    }

    @Test
    void nativeQueriesReadOnlyTheEncryptedColumns() {
        String implemented = NativeQueryEncryptor.encrypt(REPOSITORY, "employees", List.of("email", "phone")).source();
        assertThat(implemented).contains("CASE WHEN e.email_enc IS NOT NULL THEN e.email_enc ELSE e.email END AS email");

        NativeQueryEncryptor.FinalizeResult r = NativeQueryEncryptor.finalizeQueries(implemented,
            Map.of("employees", List.of("email", "phone")));
        assertThat(r.blockers()).isEmpty();
        assertThat(r.source()).doesNotContain("PiEncrypted").doesNotContain("CASE WHEN")
            .contains("SELECT e.id, e.first_name, e.email_enc AS email, e.phone_enc AS mobile")
            .contains("List<Object[]> findAllRows();");
        // same file as before Implementation, apart from the select list
        assertThat(r.source()).isEqualTo(REPOSITORY.replace("e.email, e.phone AS mobile", "e.email_enc AS email, e.phone_enc AS mobile"));
        // idempotent
        NativeQueryEncryptor.FinalizeResult again = NativeQueryEncryptor.finalizeQueries(r.source(),
            Map.of("employees", List.of("email", "phone")));
        assertThat(again.changed()).isFalse();
        assertThat(again.blockers()).isEmpty();
    }

    @Test
    void blocksNativeQueriesThatFilterOnThePlainColumn() {
        String repo = NativeQueryEncryptor.encrypt(REPOSITORY.replace("ORDER BY e.id", "WHERE e.email = :email"),
            "employees", List.of("email")).source();
        NativeQueryEncryptor.FinalizeResult r = NativeQueryEncryptor.finalizeQueries(repo, Map.of("employees", List.of("email")));
        assertThat(r.blockers()).singleElement().asString().contains("findAllRows").contains("employees.email");

        String star = REPOSITORY.replace("e.id, e.first_name, e.email, e.phone AS mobile", "*");
        assertThat(NativeQueryEncryptor.finalizeQueries(star, Map.of("employees", List.of("email"))).blockers())
            .singleElement().asString().contains("SELECT *");
    }

    @Test
    void warnsAboutQueriesOnEncryptedFields() {
        assertThat(NativeQueryEncryptor.encryptedFieldQueries(REPOSITORY, "Employee", List.of("email", "phone")))
            .containsExactly("existsByEmailIgnoreCase queries Employee.email");
        assertThat(NativeQueryEncryptor.encryptedFieldQueries(REPOSITORY, "Department", List.of("email"))).isEmpty();
    }

    @Test
    void cleanupCodeHasNoFlag() {
        assertThat(EncryptionCodeTemplates.converter("com.digithon.hr.security", "jakarta.persistence"))
            .contains("implements AttributeConverter<String, String>").doesNotContain("PiEncryptionFlags");
        assertThat(EncryptionCodeTemplates.nativeQueryDecryptor("com.digithon.hr.security"))
            .contains("query.nativeQuery()").doesNotContain("PiEncryptionFlags");
    }

    @Test
    void dropMigrationOnlyDropsExistingColumnsWithAPopulatedCopy() {
        String sql = EncryptionCodeTemplates.dropMigration(List.of(
            new EncryptionCodeTemplates.PlainColumn("public", "employees", "last_name")));
        assertThat(sql).contains("-- drop public.employees.last_name\nDO $$\nBEGIN\n"
                + "    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = "
                + "'employees' AND column_name = 'last_name') THEN\n")
            .contains("AND column_name = 'last_name_enc') THEN\n"
                + "            RAISE EXCEPTION 'public.employees.last_name_enc is missing; not dropping last_name';")
            .contains("IF EXISTS (SELECT 1 FROM \"public\".\"employees\" WHERE \"last_name\" IS NOT NULL AND "
                + "\"last_name_enc\" IS NULL) THEN\n")
            .contains("        ALTER TABLE \"public\".\"employees\" DROP COLUMN \"last_name\";\n    END IF;\nEND $$;\n")
            .contains("a stale copy is not detected");
    }
}
