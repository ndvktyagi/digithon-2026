CREATE TABLE employees (
    id BIGSERIAL PRIMARY KEY,
    first_name VARCHAR(80) NOT NULL,
    last_name VARCHAR(80) NOT NULL,
    email VARCHAR(180) NOT NULL,
    phone VARCHAR(25),
    department VARCHAR(100) NOT NULL,
    joining_date DATE NOT NULL,
    CONSTRAINT uk_employees_email UNIQUE (email)
);
CREATE INDEX idx_employees_department ON employees(department);
