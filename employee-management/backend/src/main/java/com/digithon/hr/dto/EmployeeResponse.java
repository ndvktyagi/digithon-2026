package com.digithon.hr.dto;

import java.time.LocalDate;

public record EmployeeResponse(Long id, String firstName, String lastName, String email,
                               String phone, Long departmentId, String departmentName, LocalDate joiningDate) {

    /** Constructed from the native SQL JOIN result (Object[] columns). */
    public static EmployeeResponse fromRow(Object[] row) {
        return new EmployeeResponse(
            ((Number) row[0]).longValue(),  // id
            (String) row[1],                // firstName
            (String) row[2],                // lastName
            (String) row[3],                // email
            (String) row[4],                // phone
            ((Number) row[5]).longValue(),  // departmentId
            (String) row[6],                // departmentName
            ((java.sql.Date) row[7]).toLocalDate() // joiningDate
        );
    }
}