-- Create department master table
CREATE TABLE departments (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    CONSTRAINT uk_departments_name UNIQUE (name)
);

-- Seed departments from existing employee data
INSERT INTO departments (name)
SELECT DISTINCT department FROM employees ORDER BY department;

-- Add department_id column to employees (nullable initially)
ALTER TABLE employees ADD COLUMN department_id BIGINT;

-- Populate department_id based on join with departments
UPDATE employees e SET department_id = d.id
FROM departments d WHERE e.department = d.name;

-- Make department_id NOT NULL now that it's populated
ALTER TABLE employees ALTER COLUMN department_id SET NOT NULL;

-- Drop the old department column
ALTER TABLE employees DROP COLUMN department;

-- Add foreign key constraint
ALTER TABLE employees ADD CONSTRAINT fk_employees_department
    FOREIGN KEY (department_id) REFERENCES departments(id);

-- Add index on the FK column
CREATE INDEX idx_employees_department_id ON employees(department_id);