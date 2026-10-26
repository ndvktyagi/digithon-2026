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
        SELECT e.id, e.first_name, e.last_name, e.email, e.phone,
               d.id AS department_id, d.name AS department_name, e.joining_date
        FROM employees e
        INNER JOIN departments d ON e.department_id = d.id
        ORDER BY e.id""", nativeQuery = true)
    List<Object[]> findAllWithDepartment();

    /** PI Co-Pilot: findAllWithDepartment reading the encrypted columns. PiNativeQueryRouter calls it instead of findAllWithDepartment only while encrypted read is on. */
    @Query(value = """
        SELECT e.id, e.first_name, e.last_name, CASE WHEN e.email_enc IS NOT NULL THEN e.email_enc ELSE e.email END AS email, e.phone,
               d.id AS department_id, d.name AS department_name, e.joining_date
        FROM employees e
        INNER JOIN departments d ON e.department_id = d.id
        ORDER BY e.id""", nativeQuery = true)
    List<Object[]> findAllWithDepartmentPiEncrypted();

    /** Fetch a single employee by ID joined with department using native SQL. */
    @Query(value = """
        SELECT e.id, e.first_name, e.last_name, e.email, e.phone,
               d.id AS department_id, d.name AS department_name, e.joining_date
        FROM employees e
        INNER JOIN departments d ON e.department_id = d.id
        WHERE e.id = :id""", nativeQuery = true)
    Optional<Object[]> findByIdWithDepartment(Long id);

    /** PI Co-Pilot: findByIdWithDepartment reading the encrypted columns. PiNativeQueryRouter calls it instead of findByIdWithDepartment only while encrypted read is on. */
    @Query(value = """
        SELECT e.id, e.first_name, e.last_name, CASE WHEN e.email_enc IS NOT NULL THEN e.email_enc ELSE e.email END AS email, e.phone,
               d.id AS department_id, d.name AS department_name, e.joining_date
        FROM employees e
        INNER JOIN departments d ON e.department_id = d.id
        WHERE e.id = :id""", nativeQuery = true)
    Optional<Object[]> findByIdWithDepartmentPiEncrypted(Long id);
}