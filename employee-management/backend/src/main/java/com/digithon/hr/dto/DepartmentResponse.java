package com.digithon.hr.dto;

import com.digithon.hr.entity.Department;

public record DepartmentResponse(Long id, String name) {
    public static DepartmentResponse from(Department department) {
        return new DepartmentResponse(department.getId(), department.getName());
    }
}