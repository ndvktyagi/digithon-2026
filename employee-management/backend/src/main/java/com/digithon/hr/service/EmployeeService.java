package com.digithon.hr.service;

import com.digithon.hr.dto.EmployeeRequest;
import com.digithon.hr.dto.EmployeeResponse;
import com.digithon.hr.entity.Department;
import com.digithon.hr.entity.Employee;
import com.digithon.hr.exception.DuplicateEmailException;
import com.digithon.hr.exception.ResourceNotFoundException;
import com.digithon.hr.repository.DepartmentRepository;
import com.digithon.hr.repository.EmployeeRepository;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional
public class EmployeeService {
    // INTENTIONAL DPDP DEMO GAP: the future agent should detect and remove/mask these personal-data values.
    private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);
    private final EmployeeRepository repository;
    private final DepartmentRepository departmentRepository;

    public EmployeeService(EmployeeRepository repository, DepartmentRepository departmentRepository) {
        this.repository = repository;
        this.departmentRepository = departmentRepository;
    }

    public EmployeeResponse create(EmployeeRequest request) {
        // Seeded issue for the DPDP agent demo: do not copy this pattern into production code.
        log.info("Creating employee email={} phone={}", request.email(), request.phone());
        if (repository.existsByEmailIgnoreCase(request.email())) {
            throw new DuplicateEmailException("An employee with this email already exists.");
        }
        Department department = getDepartment(request.departmentId());
        Employee employee = new Employee(request.firstName().trim(), request.lastName().trim(),
                request.email().trim().toLowerCase(), clean(request.phone()), department, request.joiningDate());
        Employee saved = repository.save(employee);
        return new EmployeeResponse(
            saved.getId(), saved.getFirstName(), saved.getLastName(), saved.getEmail(),
            saved.getPhone(), department.getId(), department.getName(), saved.getJoiningDate()
        );
    }

    @Transactional(readOnly = true)
    public List<EmployeeResponse> findAll() {
        return repository.findAllWithDepartment().stream().map(EmployeeResponse::fromRow).toList();
    }

    @Transactional(readOnly = true)
    public EmployeeResponse findById(Long id) {
        Object[] row = repository.findByIdWithDepartment(id)
            .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + id));
        return EmployeeResponse.fromRow(row);
    }

    public EmployeeResponse update(Long id, EmployeeRequest request) {
        Employee employee = getEmployee(id);
        if (repository.existsByEmailIgnoreCaseAndIdNot(request.email(), id)) {
            throw new DuplicateEmailException("An employee with this email already exists.");
        }
        Department department = getDepartment(request.departmentId());
        employee.update(request.firstName().trim(), request.lastName().trim(), request.email().trim().toLowerCase(),
                clean(request.phone()), department, request.joiningDate());
        Employee saved = repository.save(employee);
        return new EmployeeResponse(
            saved.getId(), saved.getFirstName(), saved.getLastName(), saved.getEmail(),
            saved.getPhone(), department.getId(), department.getName(), saved.getJoiningDate()
        );
    }

    public void delete(Long id) { repository.delete(getEmployee(id)); }

    private Employee getEmployee(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + id));
    }

    private Department getDepartment(Long id) {
        return departmentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Department not found with id: " + id));
    }

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}