package com.picopilot.implementation.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NativeQueryEncryptorTest {

    private static final String REPOSITORY = """
        package com.digithon.hr.repository;

        import com.digithon.hr.entity.Employee;
        import org.springframework.data.jpa.repository.JpaRepository;
        import org.springframework.data.jpa.repository.Query;

        import java.util.List;
        import java.util.Optional;

        public interface EmployeeRepository extends JpaRepository<Employee, Long> {
            boolean existsByEmailIgnoreCase(String email);

            /** Fetch all employees joined with department using native SQL. */
            @Query(value = \"""
                SELECT e.id, e.first_name, e.last_name, e.email, e.phone,
                       d.id AS department_id, d.name AS department_name, e.joining_date
                FROM employees e
                INNER JOIN departments d ON e.department_id = d.id
                ORDER BY e.id\""", nativeQuery = true)
            List<Object[]> findAllWithDepartment();

            @Query(value = \"""
                SELECT e.id, e.first_name, e.last_name, e.email, e.phone,
                       d.id AS department_id, d.name AS department_name, e.joining_date
                FROM employees e
                INNER JOIN departments d ON e.department_id = d.id
                WHERE e.id = :id\""", nativeQuery = true)
            Optional<Object[]> findByIdWithDepartment(Long id);

            @Query("select e from Employee e where e.email = :email")
            Optional<Employee> findJpql(String email);
        }
        """;

    private static final String EMAIL_CASE = "CASE WHEN e.email_enc IS NOT NULL THEN e.email_enc ELSE e.email END AS email";
    private static final String PHONE_CASE = "CASE WHEN e.phone_enc IS NOT NULL THEN e.phone_enc ELSE e.phone END AS phone";

    @Test
    void rewritesNativeQueriesInPlace() {
        NativeQueryEncryptor.Result r = NativeQueryEncryptor.encrypt(REPOSITORY, "employees", List.of("email", "phone"));
        assertThat(r.changed()).isTrue();
        assertThat(r.columnOutcomes()).containsEntry("email", NativeQueryEncryptor.UPDATED)
            .containsEntry("phone", NativeQueryEncryptor.UPDATED);
        assertThat(r.source()).isEqualTo(REPOSITORY.replace("e.email, e.phone,", EMAIL_CASE + ", " + PHONE_CASE + ","));
        assertThat(r.source()).doesNotContain(NativeQueryEncryptor.METHOD_SUFFIX);
        // the JPQL query is not touched
        assertThat(r.source()).contains("@Query(\"select e from Employee e where e.email = :email\")");
    }

    @Test
    void isIdempotent() {
        String once = NativeQueryEncryptor.encrypt(REPOSITORY, "employees", List.of("email")).source();
        NativeQueryEncryptor.Result twice = NativeQueryEncryptor.encrypt(once, "employees", List.of("email"));
        assertThat(twice.changed()).isFalse();
        assertThat(twice.source()).isEqualTo(once);
        assertThat(twice.columnOutcomes()).containsEntry("email", NativeQueryEncryptor.UPDATED);
        // a column approved later is added to the same query
        String more = NativeQueryEncryptor.encrypt(once, "employees", List.of("email", "phone")).source();
        assertThat(count(more, EMAIL_CASE)).isEqualTo(2);
        assertThat(count(more, PHONE_CASE)).isEqualTo(2);
    }

    @Test
    void rewritesEveryApprovedTableOfAJoin() {
        NativeQueryEncryptor.MultiResult r = NativeQueryEncryptor.encrypt(REPOSITORY,
            Map.of("employees", List.of("email"), "departments", List.of("name")));
        assertThat(count(r.source(), "CASE WHEN d.name_enc IS NOT NULL THEN d.name_enc ELSE d.name END AS department_name"))
            .isEqualTo(2);
        assertThat(r.outcomes().get("departments")).containsEntry("name", NativeQueryEncryptor.UPDATED);
    }

    @Test
    void ignoresTablesTheQueriesDoNotRead() {
        NativeQueryEncryptor.Result r = NativeQueryEncryptor.encrypt(REPOSITORY, "departments", List.of("budget"));
        assertThat(r.changed()).isFalse();
        assertThat(r.columnOutcomes()).isEmpty();
    }

    @Test
    void reportsStarSelectsAsUnsupported() {
        String src = "@Query(value = \"SELECT * FROM employees\", nativeQuery = true)\nList<Object[]> all();";
        NativeQueryEncryptor.Result r = NativeQueryEncryptor.encrypt(src, "employees", List.of("email"));
        assertThat(r.changed()).isFalse();
        assertThat(r.columnOutcomes()).containsEntry("email", NativeQueryEncryptor.UNSUPPORTED);
    }

    @Test
    void namesUnaliasedTableColumns() {
        String src = "interface R {\n    @Query(value = \"SELECT id, email AS mail FROM employees WHERE id = ?1\", nativeQuery = true)\n"
            + "    Object[] mail(@Param(\"id\") Long id);\n}\n";
        assertThat(NativeQueryEncryptor.encrypt(src, "employees", List.of("email")).source())
            .contains("SELECT id, CASE WHEN employees.email_enc IS NOT NULL THEN employees.email_enc ELSE employees.email END"
                + " AS mail FROM employees WHERE id = ?1");
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }
}
