package com.digithon.hr.controller;

import com.digithon.hr.dto.EmployeeRequest;
import com.digithon.hr.dto.EmployeeResponse;
import com.digithon.hr.service.EmployeeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/employees")
@Tag(name = "Employees", description = "Employee CRUD operations")
public class EmployeeController {
    private final EmployeeService service;
    public EmployeeController(EmployeeService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an employee")
    public EmployeeResponse create(@Valid @RequestBody EmployeeRequest request) { return service.create(request); }

    @GetMapping
    @Operation(summary = "List all employees")
    public List<EmployeeResponse> findAll() { return service.findAll(); }

    @GetMapping("/{id}")
    @Operation(summary = "Get an employee by ID")
    public EmployeeResponse findById(@PathVariable Long id) { return service.findById(id); }

    @PutMapping("/{id}")
    @Operation(summary = "Update an employee")
    public EmployeeResponse update(@PathVariable Long id, @Valid @RequestBody EmployeeRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an employee")
    public void delete(@PathVariable Long id) { service.delete(id); }
}
