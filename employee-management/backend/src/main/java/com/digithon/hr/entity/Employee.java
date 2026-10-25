package com.digithon.hr.entity;

import com.digithon.hr.security.EncryptedStringConverter;
import com.digithon.hr.security.PiEncryptionFlags;
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

    // PI Co-Pilot: encrypted copy of "first_name", kept in sync on save until the plain column is retired
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "first_name_enc", columnDefinition = "text")
    private String firstNameEncrypted;

    @Column(name = "last_name", nullable = false, length = 80)
    private String lastName;

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

    public Employee(String firstName, String lastName, String email, String phone, Department department, LocalDate joiningDate) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phone = phone;
        this.department = department;
        this.joiningDate = joiningDate;
    }

    public Long getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public Department getDepartment() { return department; }
    public LocalDate getJoiningDate() { return joiningDate; }

    public void update(String firstName, String lastName, String email, String phone, Department department, LocalDate joiningDate) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phone = phone;
        this.department = department;
        this.joiningDate = joiningDate;
    }

    /** PI Co-Pilot: writes encrypted copies of PI fields alongside the plain values. */
    @PrePersist
    @PreUpdate
    private void piCopilotSyncEncryptedFields() {
        this.firstNameEncrypted = this.firstName;
    }

    /** PI Co-Pilot: reads PI fields from the encrypted columns once encrypted read is enabled. */
    @PostLoad
    private void piCopilotReadEncryptedFields() {
        if (!PiEncryptionFlags.encryptedRead()) return;
        if (this.firstNameEncrypted != null) this.firstName = this.firstNameEncrypted;
    }
}