package com.digithon.hr.repository;

import com.digithon.hr.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    /** Fetch all employees joined with department using native SQL. */
    @Query(value = """
        SELECT e.id, CASE WHEN e.first_name_enc IS NOT NULL AND EXISTS (SELECT 1 FROM pi_feature_flag pi_f WHERE pi_f.flag_name = 'encrypted-read' AND pi_f.enabled) THEN e.first_name_enc ELSE e.first_name END AS first_name, e.last_name, e.email, e.phone,
               d.id AS department_id, d.name AS department_name, e.joining_date
        FROM employees e
        INNER JOIN departments d ON e.department_id = d.id
        ORDER BY e.id""", nativeQuery = true)
    List<Object[]> findAllWithDepartment();

    /** Fetch a single employee by ID joined with department using native SQL. */
    @Query(value = """
        SELECT e.id, CASE WHEN e.first_name_enc IS NOT NULL AND EXISTS (SELECT 1 FROM pi_feature_flag pi_f WHERE pi_f.flag_name = 'encrypted-read' AND pi_f.enabled) THEN e.first_name_enc ELSE e.first_name END AS first_name, e.last_name, e.email, e.phone,
               d.id AS department_id, d.name AS department_name, e.joining_date
        FROM employees e
        INNER JOIN departments d ON e.department_id = d.id
        WHERE e.id = :id""", nativeQuery = true)
    Optional<Object[]> findByIdWithDepartment(Long id);
}