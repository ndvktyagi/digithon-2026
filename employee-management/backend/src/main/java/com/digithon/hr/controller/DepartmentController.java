package com.digithon.hr.controller;

import com.digithon.hr.dto.DepartmentRequest;
import com.digithon.hr.dto.DepartmentResponse;
import com.digithon.hr.entity.Department;
import com.digithon.hr.repository.DepartmentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/departments")
@Tag(name = "Departments", description = "Department master-data operations")
public class DepartmentController {
    private final DepartmentRepository repository;

    public DepartmentController(DepartmentRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "List all departments")
    public List<DepartmentResponse> findAll() {
        return repository.findAll().stream().map(DepartmentResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a department by ID")
    public DepartmentResponse findById(@PathVariable Long id) {
        Department department = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found: " + id));
        return DepartmentResponse.from(department);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new department")
    public DepartmentResponse create(@Valid @RequestBody DepartmentRequest request) {
        Department department = new Department(request.name());
        Department saved = repository.save(department);
        return DepartmentResponse.from(saved);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing department")
    public DepartmentResponse update(@PathVariable Long id, @Valid @RequestBody DepartmentRequest request) {
        Department department = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found: " + id));
        department.setName(request.name());
        Department saved = repository.save(department);
        return DepartmentResponse.from(saved);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a department")
    public void delete(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found: " + id);
        }
        repository.deleteById(id);
    }
}